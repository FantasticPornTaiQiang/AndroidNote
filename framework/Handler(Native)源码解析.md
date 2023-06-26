# Native

首先，如果是第一次接触native层，就首先要搞清楚，native层是干什么的。

Java层有一套Handler机制，是面向Java层开发者去处理各种事件的，那么同样地，也有native的C/C++开发者，他们也需要这样一套类似的机制，去处理native层的事件（比如，屏幕触摸事件，最初是设备的硬件检测到，然后给到native层处理，然后再给到Java层的），这就是native层的作用。

那么，结合Java层和native层一起来看，先来给出完整的流程，这样能先有个大概的印象：

- 首先，Java层loopOnce，loopOnce内部调用msgQueue.next()去取消息
- 而next内部还有一个for死循环，当消息队列为空，或者当前消息还没到执行时间时，就会在这个for循环的下一次循环时去调用nativePollOnce，进入native层
- native层则调用native的pollOnce，这里面又是一个for死循环，这个循环里就是真正阻塞的地方了，如果native也没有任何事件发生，线程就会进入休眠，让出cpu调度
- 而如果有Java/native的事件发生，或者等到了超时时间，则会唤醒线程，唤醒后，先是native层去分发native层的事件执行，然后回到Java层
- 如果Java层没msg，就在next()的for里继续循环，如果有，则取出给handler去分发，分发完，就回到loopOnce的死循环，然后loopOnce继续调用next，继续下一次的消息处理

我们的App从main开始，一直就在mainLooper的loopOnce里一直循环着，直到它的进程被杀死。

---

好，现在我们大概对整个Java和Native的Handler机制有一个完整的、串起来的认识了，并且Java层也已经分析的差不多了，那么，让我们来思考Native层的细节实现。

### 休眠-唤醒机制

在上面的流程中，看起来感觉最难的，也是最重要的就是这个休眠唤醒机制了，如果Java和native都没有事件，那就休眠。

怎么实现呢？（以下大段摘抄自[https://zhuanlan.zhihu.com/p/567982370?utm_id=0](https://zhuanlan.zhihu.com/p/567982370?utm_id=0)，这篇文章写得是真好）

从 Android 2.3 开始，Google 把 Handler 的阻塞/唤醒方案从 Object#wait() / notify()，改成了用 **`Linux epoll`** 来实现。原因是 Native 层也引入了一套消息管理机制，用于提供给 C/C++ 开发者使用，而Object的阻塞/唤醒方案是为 Java 层准备的，只支持 Java。

Native 希望能够像 Java 一样：**main 线程在没有消息时进入阻塞状态，有到期消息需要执行时，main 线程能及时醒过来处理**。怎么办？有两种选择：

- 要么，继续使用 Object#wait() / notify( )，Native 向消息队列添加新消息时，通知 Java 层自己需要什么时候被唤醒。
- **要么，在 Native 层重新实现一套阻塞/唤醒方案，弃用 Object#wait() / notify()，Java 通过 jni 调用 Native 进入阻塞态**

结局我们都知道了，Google 选择了后者。

而其实如果只是将 Java 层的阻塞/唤醒移植到 Native 层，倒也不用祭出 **epoll** 这个大杀器 ，Native 调用 pthread_cond_wait 也能达到相同的效果。

选择 **epoll** 的另一个原因是， Native 层支持监听 **自定义 Fd** （*比如 Input 事件就是通过 epoll 监听 socketfd 来实现将事件转发到 APP 进程的*），而一旦有监听多个流事件的需求，那就只能使用 Linux I/O 多路复用技术。

**1、I/O多路复用**

说了这么多，那到底什么是 epoll ？

epoll 全称 eventpoll，是 Linux I/O 多路复用的其中一个实现，除了 epoll 外，还有 select 和 poll ，这里只讨论 epoll。

要理解 epoll ，我们首先需要理解什么是 "**流**"。在 Linux 中，任何可以进行 I/O 操作的对象都可以看做是流，一个 文件， socket， pipe，我们都可以把他们看作流。

接着我们来讨论流的 I/O 操作，通过调用 read() ，我们可以从流中读出数据；通过 write() ，我们可以往流写入数据。

现在假定一个情形，我们需要从流中读数据，但是流中还没有数据。

```
int socketfd= socket();
connect(socketfd,serverAddr);
int n= send(socketfd,'在吗');
n= recv(socketfd);//等待接受服务器端 发过来的信息
...//处理服务器返回的数据
```

一个典型的例子为，客户端要从 socket 中读数据，但是服务器还没有把数据传回来，这时候该怎么办？有两种方式：

- 阻塞**：** 线程阻塞到 **recv()** 方法，直到读到数据后再继续向下执行
- 非阻塞**：recv()** 方法没读到数据立刻返回 -1 ，用户线程按照固定间隔轮询 **recv()** 方法，直到有数据返回

好，现在我们有了阻塞和非阻塞两种解决方案，接着我们同时发起100个网络请求，看看这两种方案各自会怎么处理。

先说阻塞模式，在阻塞模式下，**一个线程一次只能处理一个流的 I/O 事件**，想要同时处理多个流，只能使用 **多线程 + 阻塞 I/O** 的方案。但是，**每个 socket 对应一个线程会造成很大的资源占用**，尤其是对于长连接来说，线程资源一直不会释放，如果后面陆续有很多连接的话，很快就会把机器的内存跑完。

在非阻塞模式下，我们发现 **单线程可以同时处理多个流了**。**只要不停的把所有流从头到尾的访问一遍，就可以得知哪些流有数据**（*返回值大于-1*），但这样的做法效率也不高，因为如果所有的流都没有数据，那么只会白白浪费 CPU。

发现问题了吗？只有**阻塞**和**非阻塞**这两种方案时，一旦有监听多个流事件的需求，用户程序只能选择，**要么浪费线程资源（*阻塞型 I/O*）**，**要么浪费 CPU 资源（*非阻塞型 I/O*）**，没有其他更高效的方案。

因此，这个问题在用户程序端是无解的，**必须让内核创建某种机制，把这些流的监听事件接管过去**，因为任何事件都必须通过内核读取转发，内核总是能在第一时间知晓事件发生。

**这种能够让用户程序拥有 “同时监听多个流读写事件” 的机制，就被称为 I/O 多路复用。**

然后来看 **`epoll`** 提供的三个函数：

```cpp
**int** **epoll_create**(**int** size);
**int** **epoll_ctl**(**int** epfd, **int** op, **int** fd, **struct** **epoll_event** *****event);
**int** **epoll_wait**(**int** epfd, **struct** **epoll_event** *****events, **int** maxevents, **int** timeout);
```

- **`epoll_create()`** 用于创建一个 **`epoll`** 池
- **`epoll_ctl()`** 用来执行 **`fd`** 的 **“增删改”** 操作（**`op`**），最后一个参数 **`event`** 是告诉内核 **需要监听什么事件（例如可读/可写等）**。还是以网络请求举例， **`socketfd`** 监听的就是 **`可读事件`**，一旦接收到服务器返回的数据，监听 **`socketfd`** 的对象将会收到 **回调通知**，表示 **`socket`** 中有数据可以读了
- **`epoll_wait()`** 是 **使用户线程阻塞** 的方法，它的第二个参数 **`events`** 接受的是一个 **集合对象**，如果有多个事件同时发生，**`events` 对象可以从内核得到发生的事件的集合**

**2、Linux EventFd**

理解了Epoll之后，回到主线，我们要实现休眠唤醒，还借助了一个东西，就是EventFd。

eventfd是专门用来传递事件的fd，它的功能非常简单，就是累积计数。

```cpp
int efd= eventfd();
write(efd, 1);//写入数字1
write(efd, 2);//再写入数字2
int res= read(efd);
printf(res);//输出值为 3
```

通过write()函数，我们可以向eventfd中写入一个int类型的值，并且，只要没有发生读操作，eventfd中保存的值将会一直累加。

通过read()函数可以将eventfd保存的值读了出来，并且，在没有新的值加入之前，再次调用read()方法会发生阻塞，直到有人重新向eventfd写入值。

这意味着：**只要eventfd计数不为 0 ，那么就表示fd是可读的。**

那么休眠唤醒机制的实现就有思路了。

**3、休眠唤醒机制的实现**

首先，在Java层初始化的时候，也进行native的初始化，即：Java层的MessageQueue创建时，构造方法中会调用nativeInit，进行native的初始化，具体地，Native中也有一个MessageQueue，即Native层的消息队列，同样，Native层也有一个Looper，它也是线程单例的（储存在线程局部存储区）。那么native初始化时，会new MessageQueue和Looper，然后在Looper的构造函数中，创建我们刚刚提到的EventFd和epoll实例：

- 创建EventFd对象：`mWakeEventFd` ，这个对象就是用来监听Java/native层是否有新消息
- 创建epoll池：`mEpollFd`，即之后通过这个mEpollFd去操作创建的epoll
- 创建完后，紧接着，调用epoll_ctl，即增加对mWakeEventFd的监听

这一部分的代码如下：

```cpp
/system/core/libutils/Looper.cpp
class looper {
    Looper::Looper() {
				int mWakeEventFd = eventfd();
        rebuildEpollLocked();
    }

		void rebuildEpollLocked(){
				int mEpollFd = epoll_create();
        epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeEventFd, &eventItem);
    }
}
```

至此，native的初始化工作完成，创建了重要的mWakeEventFd和mEpollFd，那么先不说阻塞，先说唤醒，借由以上介绍的内容，应该能猜到唤醒机制的实现了：

假设当前已经处于线程阻塞状态（调用了epoll_wait），而此时来了新消息，需要唤醒，则会向mWakeEventFd写入一个1，这一块代码如下：

```cpp
/system/core/libutils/Looper.cpp
classlooper {
		void Looper::wake() {
				int inc= 1;
        write(mWakeEventFd,&inc);
    }
}
```

仅仅写入一个1是怎么实现唤醒的？之前提到过，**只要eventfd计数不为 0 ，那么就表示fd是可读的，而写入一个1就导致eventfd的读写状态发生了改变，从不可读变成了可读，而我们在native初始化时，已经向epoll注册了对eventfd的读写状态的监听，这时候，内核监听到eventfd可读写状态发生变化，就会将事件从内核返回给 epoll_wait 方法调用，而epoll_wait一旦返回，阻塞态自然会被取消，线程被唤醒，继续向下执行。**

在搞懂这个逻辑之后，休眠唤醒机制的最重要的部分已经说完了，接下来，我们再从头系统地捋一捋Native的Handler机制。

---

### Native Handler机制

在Handler、MessageQueue、Looper这些角色中，只有MessageQueue有有关Native的操作，主要有这么几个：

```
static long nativeInit();
static void nativeDestroy(longptr);
void nativePollOnce(longptr,inttimeoutMillis);
static void nativeWake(longptr);
static boolean nativeIsPolling(longptr);
static void nativeSetFileDescriptorEvents(longptr,intfd,intevents);
```

那么我们肯定先从初始化看起，即nativeInit()，它在java层MessageQueue的构造函数中调用。

### 1 初始化

Java MessageQueue 构造函数中会调用 nativeInit() 方法，同步在 Native 层也会创建一个消息队列 NativeMessageQueue 对象，用于保存 Native 开发者发送的消息。nativeInit调用完后，会把这个native的MessageQueue的引用给Java层，即nativeInit的返回值是个long，存在了Java层MessageQueue的mPtr里，因为后续对native的操作，例如wake、PollOnce、destroy等，都需要这个mPtr引用。

```java
/frameworks/base/core/java/android/os/MessageQueue.java
MessageQueue(boolean quitAllowed) {
    mQuitAllowed = quitAllowed;
    mPtr = nativeInit();
}
```

Native的MessageQueue构造时，也会创建一个native的Looper对象，它也是线程单例的。Native 创建 Looper 对象的处理逻辑和 Java 一样：先去 线程局部存储区 获取 Looper 对象，如果为空，创建一个新的 Looper 对象并保存到 线程局部存储区。

```cpp
/frameworks/base/core/jni/android_os_MessageQueue.cpp
class android_os_MessageQueue {

    void android_os_MessageQueue_nativeInit() {
        NativeMessageQueue* nativeMessageQueue = new NativeMessageQueue();
    }

    NativeMessageQueue() {
        mLooper = Looper::getForThread();
        if (mLooper == NULL) {
            mLooper = new Looper(false);
            Looper::setForThread(mLooper);
        }
    }
}
```

然后，接着看Native的Looper的初始化，这就是我们之前提到的，创建关键的mWakeEventFd和mEpollFd对象。

```cpp
/system/core/libutils/Looper.cpp
class looper {
    Looper::Looper() {
        int mWakeEventFd = eventfd();
        rebuildEpollLocked();
    }

    void rebuildEpollLocked(){
        int mEpollFd = epoll_create();
        epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeEventFd, & eventItem);
    }
}
```

NativeInit的关键步骤就讲到这里，下面看看消息的循环与阻塞流程，也就是涉及到nativePollOnce的部分。

### 2 消息的循环与线程的休眠

Java 和 Native 的消息队列都创建完以后，整个线程就会执行到 Looper#loop() 方法中，在 Java 层的的调用链大致是这样的：

```
Looper#loop()
-> MessageQueue#next()
-> MessageQueue#nativePollOnce()
```

然后进入nativePollOnce方法。

```cpp
/frameworks/base/core/jni/android_os_MessageQueue.cpp
class android_os_MessageQueue {

    //jni方法，转到 NativeMessageQueue#pollOnce()
    void android_os_MessageQueue_nativePollOnce(){
        nativeMessageQueue->pollOnce(env, obj, timeoutMillis);
    }

    class NativeMessageQueue : MessageQueue {
        //转到 Looper#pollOnce() 方法
        void pollOnce(){
            mLooper->pollOnce(timeoutMillis);
        }
    }
}
```

NativeMessageQueue#pollOnce() 中什么都没做，只是又把请求转发给了 Looper#pollOnce() ，看来主要的逻辑都在 Looper 中。

```cpp
/system/core/libutils/Looper.cpp
class looper {

    int pollOnce(int timeoutMillis){
        int result = 0;
        for (;;) {
            if (result != 0) {
                return result;
            }
            result = pollInner(timeoutMillis);
        }
    }

    int pollInner(int timeoutMillis) {
				...
		    int eventCount = epoll_wait(
					mEpollFd, eventItems, EPOLL_MAX_EVENTS, timeoutMillis);
				...
    }
}
```

可以看到，Looper的pollOnce也类似Java层的Looper的loopOnce，once就是只执行一次，也就是开启了一个死循环。pollOnce的死循环中一直等待pollInner返回结果，这里的result从代码来看只要返回了，应该就不会为0。而我们从Java层传过来的timeoutMillis，也一步步被传到了这里。

result有以下四种取值：

- -1 表示在 “超时时间到期” 之前使用 **wake()** 唤醒了轮询，通常是有需要立刻执行的新消息加入了队列
- -2 表示多个事件同时发生，有可能是新消息加入，也有可能是监听的 自定义 fd 发生了 I/O 事件
- -3 表示设定的超时时间到期了
- -4 表示错误，不知道哪里会用到

在pollInner中，执行了真正的线程休眠的函数，也就是之前提到的epoll_wait，线程将在这里休眠，并等待事件唤醒。接下来，我们发送一条消息去唤醒它。

### 3 消息的发送与线程的唤醒

好，现在的消息队列里面是空的，并且经过上一小节的分析后，用户线程阻塞到了 native 层的 Looper#pollInner() 方法，我们来向消息队列发送一条消息唤醒它。

前面我们说了，Java 和 Native 都各自维护了一套消息队列，所以他们发送消息的入口也不一样，Java 开发使用 Handler#sendMessage/post，C/C++ 开发使用 Looper#sendMessage方法。

在Java层，之前分析过，如果新入队的消息需要立刻唤醒线程，就会调用nativeWake，native的Wake最终调用到 Native Looper 中的 wake，而同样，native层的C++开发者也使用与Java层类似的方式，发送消息，如果需要唤醒，则调用Looper的wake。它们最后都调用到了Looper的wake方法。而wake方法的内容，我们之前也提过了，就是向eventfd管道写入1，这就导致epoll_wait返回，用户线程唤醒，消息处理继续进行。

```cpp
/system/core/libutils/Looper.cpp
class looper {

    void Looper::wake() {
        int inc = 1;
        write(mWakeEventFd, &inc);
    }
}
```

那么，接下来就快到尾声了，也就是唤醒后的消息分发处理环节。

### 4 线程****唤醒后消息的分发处理****

线程在没有消息需要处理时会在 Looper 中的 pollInner() 方法调用中休眠，线程唤醒以后同样也是在 pollInner() 方法中继续执行。线程醒来以后，先判断自己为什么醒过来，再根据唤醒类型执行不同的逻辑。大致分为5步：

```cpp
/system/core/libutils/Looper.cpp
class looper {

    int pollInner(int timeoutMillis){
        int result = POLL_WAKE;
        // step 1，epoll_wait 方法返回
        int eventCount = epoll_wait(mEpollFd, eventItems, timeoutMillis); 
        if (eventCount == 0) { // 事件数量为0表示，达到设定的超时时间
            result = POLL_TIMEOUT;
        }
        for (int i = 0; i < eventCount; i++) {
            if (eventItems[i] == mWakeEventFd) {
                // step 2 ，清空 eventfd，使之重新变为可读监听的 fd
                awoken();
            } else {
                // step 3 ，保存自定义fd触发的事件集合
                mResponses.push(eventItems[i]);
            }
        }
        // step 4 ，执行 native 消息分发
        while (mMessageEnvelopes.size() != 0) {
            if (messageEnvelope.uptime <= now) { // 检查消息是否到期
                messageEnvelope.handler->handleMessage(message);
            }
        }
        // step 5 ，执行 自定义 fd 回调
        for (size_t i = 0; i < mResponses.size(); i++) {
            response.request.callback->handleEvent(fd, events, data);
        }
        return result;
    }

    void awoken() {
        read(mWakeEventFd) ;// 重新变成可读事件
    }

}
```

**step 1 ：** **epoll_wait** 方法返回说明有事件发生，返回值 **eventCount** 是发生事件的数量。如果为0，表示达到设定的超时时间，下面的判断逻辑都不会走，不为0，那么我们开始遍历内核返回的事件集合 **eventItems**，根据类型执行不同的逻辑。

**step 2 ：** 如果事件类型是消息队列的 **eventfd** ，说明有人向消息队列提交了需要马上执行的消息，我们只需把消息队列的 **eventfd** 数据读出来，使他重新变成可以触发 **可读事件** 的 **fd**，然后等待方法结束就行了。

**step 3 ：** 事件不是消息队列的 **eventfd** ，说明有其他地方注册了监听 **fd**，那么，我们将发生的事件保存到 **mResponses** 集合中，待会需要对这个事件做出响应，通知注册对象。

**step 4 ：** 遍历 Native 的消息集合 **mMessageEnvelopes**，检查每个消息的到期时间，如果消息到期了，交给 handler 执行分发，分发逻辑参考 Java Handler。

**step 5 ：** 遍历 **mResponses** 集合，把其他地方注册的 **自定义 fd** 消费掉，响应它们的回调方法。

唤醒后执行的逻辑还是非常复杂的，我们总结一下：

用户线程被唤醒后，优先分发 Native 层的消息，紧接着，通知 **自定义 fd** 发生的事件（*如果有的话*），最后 **pollInner()** 方法结束，返回到 Java 层 **Looper#loop()** 方法执行到 Java 层的消息分发。只有当 Java Handler 执行完消息分发，一次 **loop()** 循环才算是完成。

而后，由于Java的looper的loopOnce也处于死循环，所以就开始了下一趟调用next()取消息、然后分发的流程，周而复始，整个APP进程从main方法开始，一生都将执行这样的工作。

**至此，Java层和Native层的Handler机制，分析结束。**

---

### 补充知识

**1、文件描述符fd（file descriptor）**

- 在linux系统中有这么一句话：“一切皆文件”，而这些文件又分为：普通文件、目录文件、符号链接文件和设备文件等。对于内核来说，所有打开的文件都通过文件描述符来进行管理引用，文件描述符是一个非负整数，当打开一个文件或新建一个文件时，内核会向进程返回一个文件描述符，当所有对文件进行读写操作的read/write系统调用都是通过该文件描述符来实现的。
- 一般来说，一个进程刚启动时就默认打开了3个文件描述符，0是标准输入，1是标准输出，2是标准错误。如果此时去打开一个新的文件，它的文件描述符会是3，因为前三个默认被系统占用，POSIX标准要求每次打开文件时必须使用当前进程中最小可用的文件描述符。

**2、JNI调用的大概认识**

（[https://blog.csdn.net/tww85/article/details/52485448](https://blog.csdn.net/tww85/article/details/52485448)）

**（1）为什么需要native？**

1. 不可反编译。native 编译出来的是目标代码，不可反编译。java编译出来的中间代码可反编译，可反编译可能会导致自己设计思想或者设计算法泄露，很多商业公司是极力避免的。
2. 执行速度快。native 代码一般用c/c++ 实现，执行速度上要比java快些。
3. 方便移植。有些应用在没有android之前，都是用c/c++ 开发的，那么现在要移植到android上，直接移植到native层就不需要另外编写java代码，省时省力。

**（2）Java层如何识别native函数？**

native函数声明会带有native关键字，编译的时候就把这个native标志位加入到java函数符号表里，这样就可以和普通的函数区分开了，运行过程虚拟机执行到native函数时就会切换到native执行环境。

**（3）Java到native的调用是如何实现的？**

1. 每个虚拟机进程都对应一个JNI环境(JNIEnv)
2. 每个native方法都需要向这个JNI环境测试注册
3. 这些native方法及实现会被编译成一个动态so库
4. Java层在执行到native代码前需要把这个动态库load进来。之后整个虚拟机进程的JNI环境就包含了native方法入口。
5. Java层在执行到native代码时就能通过JNI环境知道native层对应实现。

**（4）Java和native工作在同一进程吗？**

Java和native工作在同一进程，一个应用对应一个进程，native是由应用进程load的进来并启动的，所以肯定都是在同一个进程运行，在同一个进程空间。

由于Java和native代码工作在同一进程中，因此它们可以共享内存，并且可以直接访问彼此的数据结构。这使得JNI调用非常高效，并且可以在Java和本地代码之间传递大量的数据。

**（5）native受ART的管理吗？**

native和ART是同一等级的东西，不受ART管理。ART是用来对java代码进行解析执行，同时负责堆栈管理，线程管理，对象生命周期管理，内存分配垃圾回收等。ART虚拟机本身也是基于c/c++ 编写的，是可执行的目标文件，native 层同样也是可执行的目标文件，所以它们应该是平级的，由linux系统管理运行。因此native层的内存管理，线程管理也是由linux系统管理的。

**3、JVM和操作系统线程的关系**

在大多数情况下，一个JVM线程会对应一个操作系统线程。但是，这并不是绝对的，因为JVM可以使用一些技术来优化线程的使用，如线程池、协程等。此外，一些操作系统也可以使用多个线程来处理一个JVM线程。因此，具体情况取决于JVM实现和操作系统的实现。

JVM线程和操作系统线程是不同的概念，但它们之间有一定的关系。JVM线程是由JVM管理的，它们运行在操作系统线程之上。JVM线程是Java虚拟机内部的概念，它们的生命周期和Java虚拟机的生命周期相同。而操作系统线程是由操作系统管理的，它们是操作系统内部的概念。JVM线程和操作系统线程之间的关系是一对一或一对多的关系，即一个JVM线程可能对应多个操作系统线程，这取决于操作系统的实现和JVM的配置。

在实际应用中，通常采用JVM线程和操作系统线程一对一或一对多的关系，即一个JVM线程对应一个或多个操作系统线程，这样可以更好地控制线程的数量和调度。多对多的关系可能会导致线程数量过多，从而影响系统性能。

**4、协程和线程**

协程和线程都是实现并发的方式，但是它们有一些重要的区别。

线程是操作系统调度的最小执行单位，由操作系统负责线程的创建、调度和销毁。线程之间切换时需要切换上下文，这会带来一定的开销。线程之间共享进程的资源，包括内存、文件句柄等，需要通过锁等机制来保证线程之间的同步和互斥。

协程是一种用户态的轻量级线程，可以在一个线程内实现多个协程的切换，因此不需要线程切换的开销。协程之间可以共享数据，但是需要开发者自己管理协程之间的同步和互斥。

相比于线程，协程的优势在于更高的并发性能和更低的资源消耗。但是协程也有一些限制，比如不能进行阻塞操作，因为阻塞一个协程会阻塞整个线程。