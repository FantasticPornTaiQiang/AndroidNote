# Handler

---

## Handler 机制概述

Android 应用中触摸事件的分发、View 的绘制、屏幕的刷新以及 Activity 的生命周期等都是基于消息实现的。这意味着在 Android 应用中随时都在产生大量的 Message，同时也有大量的 Message 被消费掉。

另外在 Android 系统中，UI 更新只能在主线程中进行。因此，为了更流畅的页面渲染，所有的耗时操作包括网络请求、文件读写、资源文件的解析等都应该放到子线程中。在这一场景下，线程间的通信就显得尤为重要。因为我们需要在网络请求、文件读写或者资源解析后将得到的数据交给主线程去进行页面渲染。

那在这样的背景下，就需要一套消息机制，即具有缓冲功能又能实现线程切换，而”生产者-消费者“模型正好能实现这个需求。因此 Handler 的设计就采用了生产者-消费者这一模型。

（[https://juejin.cn/post/7110625878320775204](https://juejin.cn/post/7110625878320775204)）

或者换一种说法：例如网络请求等操作是要在其他线程，那么多个线程同时对同一个 UI 控件进行更新，容易发生不可控的错误。那么，最简单的处理方式就是加锁，但不是加一个，而是每层都要加锁，但这样也意味着更多的耗时，也更容易出错。而如果每一层共用一把锁的话，其实就是单线程，所以 Android 的消息机制没有采用线程锁，而是采用单线程的消息队列机制。

（[https://juejin.cn/post/6844904150140977165#heading-0](https://juejin.cn/post/6844904150140977165#heading-0)）

## 角色

Handler 机制，或者说这种生产者消费者模型，可以当作一个传送带运送货物。

[https://juejin.cn/post/7247058712195760184](https://juejin.cn/post/7247058712195760184)

1. Handler：发送消息、处理消息。就是传送带的入口、出口（或者理解为工厂工作人员）。
   - `obtainMessage` 经由 Handler.obtainMessage 获取的消息，target 会被设置为当前 handler
   - `postxx` 还有 postAtTime、postDelayed 等，post 是 post 一个 callback，调用了 send
   - `sendxx` 同样有 sendAtTime、sendDelayed 等，直接发送消息
   - `removexx` 移除 msg、callback 等
2. Looper：实际控制当前线程的消息循环，可以把它类比为传送带，一直在转，协调 MsgQueue 和 Handler。
3. Message：消息实体，本身是链表结构。就是传送带上的货物。
4. MessageQueue：抽象的消息队列，能方便地操作 Message 链表。即操作货物队列的辅助工具。
5. IdleHandler：可以理解为一种优先级最低的消息。

**理解**：一个线程就是一个场景，一个场景内只能有一条传送带，但是这条传送带可以有多个出入口，当出口是通往别的场景时，就实现了不同场景下的货物传递。在同一场景内，传送带上，货物进入传送带是按照要被处理的时间的先后顺序排列的，因此传送带只管一直转，然后货物总会按照时间顺序被分发给不同出口。

**翻译：**一个线程内只能有一个 Looper，但是这个 Looper 可以有多个 Handler，当 Handler 是别的线程下的 Handler 时，就实现了不同线程下的 Message 传递。在同一线程内，Looper 中，Message 进入消息队列时，会按照要被处理的时间的先后顺序排列，因此 Looper 一直死循环，然后 Message 会在目标时间到来时，分发给相应 Handler 处理。

### Message

**Q1：Message 复用相关**

看 Message 的代码就能知道，它可能是一个比较大的对象，有很多属性，而且，因为 Android 庞大的系统需要处理各种消息，因此 Message 实例的数量可能会非常多，因此，可以采取复用的办法，避免因频繁创建销毁实例而导致的内存抖动。

具体地，Message 类使用静态变量 sPool 作为复用池，说是池，实际上就是一个 Message 对象，因为 Message 本身含有 next，可以看作是一个链表结构，也就是一个池。调用 Message.recycle()，如果这个 message 没有在使用中，就会让它进入这个池，池大小为 50，进池和取出复用时，都是上锁操作。

注意：obtain 时如果有多个线程取 sPool，会阻塞其它线程后面的 new Message()

```kotlin
public static Message obtain() {
        synchronized (sPoolSync) { //sPoolSync是锁，是类的static变量
            if (sPool != null) {
                //...
                return m;
            }
        }
        return new Message();
}
```

**Q2：Message 有哪些属性，怎么理解？**

- `*what*` Int 用于标识 Message，每个 Handler 独立拥有命名空间，无须担心 int 值重复
- _`arg1、arg2`_ Int 少量数据
- `*obj`\* Object 任意数据
- `*replyTo*` Messenger 用于 IPC
- `*flag*` Int 若消息被使用则设置为 True。当消息被加入队列时，该标志被设置，并在消息被传递和回收后保持原样。仅当创建或获取新消息时，该标志才被清除，因为这是应用程序允许修改消息内容的唯一时间。尝试将已在使用的消息加入队列或回收会导致错误。
  - int 作为标识：
    - **_`FLAG =_ 1** << x` 左移 x 位，定义不同的标识
    - _`flags_ |= **_FLAG`_** 标识位，置 true
    - _``flags &_= ~**_FLAG`_** 标识位，置 false
    - `(*flags* & ***FLAG_IN_USE***) == ***FLAG_IN_USE`\*\*\* 判断标识位是否 true
  - 这个方式在 View 体系中同样用到了
- _`when`_ Long 要被处理的时间，从系统启动时间开始算（deep sleep 时不计时）
- _`data`_ Bundle 数据（大量）
- _`target`_ Handler 消息发给谁去被处理（同步屏障消息没有 target）
- _`callback`_ Runnable 处理消息的回调，如果这个不为空则消息优先给这个回调处理。
  - 即，当 target 的 Handler 正在处理此消息时，如果 callback 没设置，这个消息将分派到接收 Handler 的 Handler.handleMessage(Message)方法。
- `next` Message 下一条消息（消息的链表结构靠的就是这个）

Message 类的 static：

- **_`sPoolSync` Object obtain 和 recycle 时的锁_**
- **_`sPool` Message recycle 的池（因为是链表结构，所以只要头部 message 对象就行）_**

### MessageQueue

**Q1：Message 本身就是一个链表结构了（含有 next 指向下一个消息），为什么还要有 MessageQueue？难道一个 MessageQueue 里有多条 Message 链表吗？**

不，一个 MessageQueue 内部只含有唯一一个 Message 对象，这个 Message 对象是链表的头部，代表了整个链表。MessageQueue 只是为了方便对 Message 进行遍历和其他操作而封装的类，实际上一个头部的 Message 本身就代表一条链表了。

更进一步，一个 Looper 内部只含有一个 MessageQueue，因此可以说，一个 Looper 内部就只有一条 Message 链表，这个链表一般是先进先出的（毕竟是 Queue 嘛，但也可能被异步消息插队）。

**Q2：IdleHandler？**

IdleHandler 是一个接口，在系统空闲且消息队列为空时，会处理 IdleHandler 的消息。

IdleHandler 的目的是在 UI 线程不繁忙时执行后台任务。当添加到消息队列中时，IdleHandler 会在主线程空闲时被调用，从而允许系统在不影响 UI 性能的情况下执行后台任务。
IdleHandler 接口只有一个方法 queueIdle，当主线程空闲时会被消息队列调用。通过实现这个方法，可以自定义在系统空闲时要执行的操作。

IdleHandler 可以应用到一些后台任务处理，这些任务不需要立即执行，可以随着时间的推移执行。这也能提高 UI 线程的响应速度并保持 UI 的流畅性，例子：onDestroy()

同时，IdleHandler 也可以用于**性能优化**，例如，可以在 IdleHandler 中完成一些预加载操作，以优化速度，例如，RecyclerView 分页，空闲时间提前去加载下一页等**（存疑）**，总之用法可以很灵活，可以把空闲时间都给利用上。

**Q3：同步屏障？**

**MessageQueue.postSyncBarrier**

- 在 Looper 的消息队列中发布一个同步屏障。消息处理会像平常一样进行，直到遇到已发布的同步屏障。遇到屏障之后，队列中后续的同步消息会被暂停（防止执行），直到调用 removeSyncBarrier 方法并指定标识屏障的令牌来释放屏障。这个方法用于立即推迟所有后续发布的同步消息的执行，直到满足释放屏障的条件。异步消息（参见 Message.isAsynchronous）可以免于同步屏障的影响，继续按照平时的方式被处理。为确保消息队列恢复正常操作，这个调用必须始终与使用相同令牌的 removeSyncBarrier 调用相匹配。否则，应用程序可能会挂起！
  返回值：
  唯一标识屏障的令牌。必须将此令牌传递给 removeSyncBarrier 以释放屏障
- 同步屏障本身也是一条消息，其 target 为 null，它在整个消息队列（包括同步消息）中也是按 when 顺序一起参与排序的。

**Message.setAsychronous**

- 设置消息是否为异步，意味着它不受 Looper 同步屏障的限制。
  某些操作，例如视图失效操作，可能会在 Looper 的消息队列中引入同步屏障，以防止在满足某些条件之前传递后续消息。在视图失效操作的情况下，调用 android.view.View.invalidate 之后发布的消息通过同步屏障暂停，直到下一帧准备好进行绘制。同步屏障确保在恢复之前对失效请求进行了完全处理。
  异步消息不受同步屏障的限制。它们通常表示中断、输入事件和其他必须在其他工作暂停的情况下独立处理的信号。
- 可以使用 msg.setAsychronous 来设置这条消息为异步，也可以直接将 Handler 设置为异步的，这样所有它发送的消息就也是异步。

**Q4：消息入队？**

首先，检查 message 是否符合条件，然后把消息标记为 use，然后给消息的 when 赋值，然后把消息按 when 插入到对应位置，有可能是头部，有可能是中间，这部分都很好理解，难理解的是，enqueue 的时候有若干个条件判断是否需要唤醒 native，这个判断的理由（后面有分析）。

**Q5：Handler 负责处理消息，但如果 Handler 处理完了消息，但是不移除消息，会造成内存浪费吗？**

不会，虽然在 handler 的 dispatchMessage 方法没见到 remove 相关，但不要紧，这条消息一定会在 Looper#loopOnce 循环中被回收。

一部分 remove 的应用场景是，例如，Service、BroadcastReceive 等的 ANR 机制中，是先将超时 msg 加入 handler（埋炸弹），到了时间就直接处理这条超时 msg（引爆炸弹，引发 ANR），而如果在规定时间之前执行了，则 remove 这条超时 msg（拆炸弹），而一般情况下，不需要手动 remove，消息也会在 loopOnce 循环中回收掉，手动 remove 的大多是还未处理的消息。

**Q6：多线程操作 MessageQueue 的有序性？**

在 MessageQueue 中，消息进入和移除都需要加锁（synchronized(this)），因为可能有多个线程同时访问这个线程下的 MessageQueue，必须保证他们的有序性。

这就好比，在当前场景内，传送带上，有可能有来自其他场景的入口，那么这几个入口在往传送带上投递货物时，因为传送带上的货物需要按处理时间排序，那么就必须保证这几个入口往传送带上投递货物的时间有序性。这是通过 synchronized 锁来实现的。

### Looper

Looper 实际控制消息循环，与线程挂钩，一个线程只能有一个 Looper（这是靠 ThreadLocal 实现的），main 的 looper 是 Looper 类的静态变量，全局唯一。

Looper 内部维护了当前线程、当前线程的消息队列，同时，这个消息队列也被（若干个）Handler 持有。一个线程只有一个 Looper、一个 MessageQueue，但可以有多个 Handler。

### Handler

Handler 机制中，Handler 是消息的生产者，也可以是消费者。消息的分发和处理流程如下：

- 如果 msg 本身有 callback，则交给 msg 自身的 callback 处理，return；
- 否则，
  - 如果 handler 自身有 callback，则交给 handler 自身的 callback 处理，return；
  - 否则，交给 handler 的（子类的）handleMessage 处理

这里，handler 可以给自身设置 callback，也可以通过继承 Handler 类，重写其中的 handleMessage 方法来处理。Handler 类提供了给自身设 callback 的能力就是为了避免必须继承出一个子类来处理消息。

此外，不难想到，Handler 机制能实现不同线程下通信的原因：发消息的 Handler 和处理消息的 Handler 可以不在一个线程。

### Handler 机制的完整流程

Looper 在 loop 后，开启**死循环 1**，死循环 1 是最外层的死循环，它会调用 loopOnce，loopOnce 的返回值控制着死循环 1 是否退出。

**Looper#loopOnce**

这里面调用了 messageQueue.next()去获取下一条消息来处理，而这个 next()是个阻塞方法：

- 如果 next()返回了空，则 loopOnce 返回 false，死循环 1 退出，整个线程的 Looper 停止工作了。
- 如果取到了消息，则分发给对应的 Handler，这里的 msg.target 不会为空（不会是同步屏障消息）。从这里也可以看出，Looper 的职责只是控制消息循环。最后，在这一轮循环（即死循环 1 的循环）结束前，回收复用 msg。

所以其实重要的部分是 next()，即阻塞取消息的方法，但在继续分析 next 之前，来看几个问题。

**Q1：一定是主线程才能更新 UI 吗？**

首先要知道，更新 UI 时的 checkThread 是在 ViewRootImpl 中的，它的原话是：`Only the original thread that created a view hierarchy can touch its views.` ，而并没有说主线程不行，而是 ViewRootImpl 在哪个线程创建，就得在哪个线程更新，这么设计是为了保证 UI 更新不会错乱（即开头提到的“Android 的消息机制没有采用线程锁，而是采用单线程”），因此，在新开一个线程，弄一个 dialog 出来，这个 dialog 如果在新线程里创建，则在新线程里更新它，也是可以的。只是因为我们的 Activity 的 Window 的 ViewRootImpl 在主线程创建，所以我们更新 Activity 的 UI 也得在主线程。

Q2：**Looper 在主线程中死循环，为啥不会 ANR？**

首先要明确一点，整个 APP 的程序进程在 ActivityThread 的 main 里开始运行，然后 main 方法的最后，就是 Looper.loop()，一直死循环，想让 APP 一直运行而不退出，就是得去在 main 里死循环，不断等待消息去处理。

当没有新消息时，会进入休眠，而如果有新的消息，则是系统以 binder 跨进程的方式，通知到 App 所在进程的 ApplicationThread（_ApplicationThread 是 ActivityThread 的私有变量，也是一个 Binder 对象，ApplicationThread 是 Client 端的 Binder_），然后通过 Handler 机制，往 ActivityThread（_注意这是个类，而不是个线程_）的 messageQueue 中扔消息，唤醒 App 的主线程，然后继续事件分发。

---

而 ANR 是另一个概念，和 Looper 死循环不是一个东西，不要混淆。ANR 是指应用程序未响应，Android 系统对于一些事件需要在一定的时间范围内完成，如果超过预定时间能未能得到有效响应或者响应时间过长，就会造成 ANR。一般地，这时往往会弹出一个提示框，告知用户当前 xxx 未响应，用户可选择继续等待或者 Force Close。

例如以下一些场景，会造成 ANR：

- Service Timeout：前台服务在 20s 内未执行完成（后台则是 200s）
- BroadcastQueue Timeout：前台广播在 10s 内未执行完成（后台则是 60s）
- ContentProvider Timeout：内容提供者，在 publish 过超时 10s
- InputDispatching Timeout：输入事件分发超时 5s，包括按键和触摸事件等

以 Service 为例，在 bindService 后，会触发 ActiveServices 的 realStartServiceLocked 方法，然后它会调用 bumpServiceExecutingLocked 方法，让这个 service 进入执行状态，然后在这儿会调用 scheduleServiceTimeoutLocked 埋下一个炸弹（AMS 的*`SERVICE_TIMEOUT_MSG`*），在 scheduleServiceTimeoutLocked 中：

```kotlin
void scheduleServiceTimeoutLocked(ProcessRecord proc) {
   Message msg = mAm.mHandler.obtainMessage(ActivityManagerService.SERVICE_TIMEOUT_MSG);
   msg.obj = proc;
   mAm.mHandler.sendMessageDelayed(msg, proc.mServices.shouldExecServicesFg()
                ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
}
```

会向 ActivityManagerService 的 mainHandler 发个 delay 的消息，这就埋下了一颗炸弹。

刚刚我们在 system_server 进程 AS.realStartServiceLocked()调用的过程会埋下一颗炸弹，超时没有启动完成则会爆炸。那么接下来有两种可能，一是按时完成任务，拆除炸弹引线，二是未按时完成，引爆炸弹。

- 先看按时完成：在 Binder 的层层调用下，进入目标进程的主线程，在 ActivityThread 的 handleCreateService 方法中，会创建目标服务对象，以及回调 onCreate()方法，紧接再次经过多次调用回到 system_server 来执行 AS.serviceDoneExecuting，然后在 AS.serviceDoneExecutingLocked 中拆除炸弹引线（remove*`SERVICE_TIMEOUT_MSG`* ）。
- 但如果未按时完成：刚刚 mAm.mHandler 的 delay 消息就会发送，并在自己重写的 handleMessage 中处理这个*`SERVICE_TIMEOUT_MSG`*消息，然后这个会调回 AS.serviceTimeout 方法，然后在 serviceTimeout 方法中再经过一系列判断确认 ANR 后，会调用 AMS 的 AnrHelper 的 appNotResponding 方法，然后这个方法把 Anr 存成 AnrRecord 记录，然后会启一个 AnrConsumerThread 线程来处理 List<AnrRecord>，然后对每一条 Anr 处理，调用 AnrRecord 的 appNotResponding。对于每一条 AnrRecord，它会调用 当前 App 进程 ProcessRecord 的 ProcessErrorStateRecord 类实例 mErrorState 的 appNotResponding，然后在 ProcessErrorStateRecord 的 appNotResponding 的最后：
  ```kotlin
  // Bring up the infamous App Not Responding dialog
  Messagemsg=Message.obtain();
  msg.what=ActivityManagerService.SHOW_NOT_RESPONDING_UI_MSG;
  msg.obj=newAppNotRespondingDialog.Data(mApp,aInfo,aboveSystem);

  mService.mUiHandler.sendMessageDelayed(msg,anrDialogDelayMs);
  ```
  用 app 主线程的 Handler，处理 SHOW_NOT_RESPONDING_UI_MSG，弹出 ANR 对话框。

---

因此，回到 Q2，从这个 ANR 的流程分析来看，ANR 的发生和主线程 Looper 无限循环没有关系，甚至于说，ANR 都跨到 system_server 进程去了。

☆**Q3：loopOnce 循环中，如果没有消息时 MessageQueue.next()会阻塞，说一下具体过程？（这里就要深入 native 了）**

loopOnce 本身处于前文提到的死循环 1 中，然后，loopOnce 内调用了 mq.next()，next()内又开启了一个**死循环 2**。

为了不被搞糊涂，首先需要明确这两件事（这不是具体流程）：

- 一次 next 调用就只负责取一条消息
  - 没取到则阻塞在死循环 2
  - 取到了就跳回 loopOnce 所在的死循环 1
- 一次 next 调用里，当有 IdleHandler、且没有消息（或者消息还没到执行时间）时，会遍历一次所有 IdleHandler，然后执行它们的回调
  - 通过 IdleHandler 的 callback 的返回值判断了是否该把这个 IdleHandler 移除
  - IdleHandler 的回调，一次 next()只会执行一次，即，next 里面是一个死循环 2，也就是只有死循环 2 的第一遍循环，才会去执行 IdleHandler

接着来看具体的流程。

☆**next()具体的流程：**

- 第一次进入死循环 2：
  （1）加锁，synchronized(this)
  （2）取消息，分两种情况：
  - 如果队头是同步屏障消息，则遍历它后面的消息，直到找到第一条异步的消息取出
  - 否则直接取队头的普通同步消息
  （3）取消息后，
  - 若取到了一条消息，则判断取到的消息的 when 是否小于当前时间：
    - 若小于，则可以分发（_所以如果一条消息的 when=0，是一定会分发的，不会阻塞的，因为当前时间取的是 SystemClock.uptimeMills，一定大于 0_），mBlocked=false，且 msg.markInUse()，然后整个 next()返回 msg，继续走 Looper 的 loopOnce 循环（死循环 1）流程，即把这个 msg 分给 handler 去 dispatch，dispatch 完毕后，loopOnce 就继续下一轮 next()了，即回到流程（1）重新开始
    - 若大于，则表示还没到分发的时候，还要等，所以就阻塞，nextPollTimeoutMills 变量表示阻塞的时间，即阻塞 msg.when-now 毫秒
  - 若没取到一条消息，则 nextPollTimeoutMills=-1（若有 IdleHandler 则这个 nextPollTimeoutMills 会置 0），而-1 则意味着无限等待
    - 若没取到消息、也没有 IdleHandler，然后外面也不调用 msgQueue.quit 结束的话，就会一直在死循环 2 里死循环，next 方法就一直阻塞，直到有新消息/IdleHandler 或者 quit 了，所以，native 的阻塞会发生在有消息，但 msg.when>now 时，或没消息时
  （4）当没有消息，或者消息还没到执行时间，即 msg.when>now（说白了也就是 Idle）时，会遍历 IdleHandler，把它们加入一个临时的集合（这是为了一次 next 中只执行一次他们），然后离开锁
  （5）如果 IdleHandler 存在，则调用它们。只有第一次进入死循环 2 会调用一遍。
- 如果第一次进死循环 2，取到了消息，则 next()直接返回了，但如果没取到，要阻塞，之前计算了阻塞的时间，于是进入死循环 2 的第二轮时：
  （1）如果 nextPollTimeoutMills≠0（≥0 则阻塞相应毫秒数，＜ 0 则无限阻塞），则 flush 所有有关的 Binder 指令，然后开始 nativePollOnce，即 native 层阻塞，阻塞的时间就是 nextPollTimeoutMills，因为阻塞这个时间之后，当前 now 就一定大于等于了之前取到的 msg.when 了，唤醒后，就可以取刚才的第一条消息去分发了。
  - 上述是没有新消息加入的情况，但如果在 nativePollOnce 阻塞的期间，Java 层有新消息来了（MessageQueue.enqueue），那么，会视情况（与 mBlock 有关）判断要不要提前结束 native 层（nativeWake），即 nativePollOnce 提前返回。具体地，enqueue 时：
    - 以下情况会需要执行 nativeWake，提前结束 nativePollOnce（其他情况正常按 when 去排序入队就行）：
      - 当前队列为空**_&&_**当前已阻塞在 nativePollOnce（所以这条新的消息肯定是要被分发的）
      - 新消息的 when=0**_&&_**当前已阻塞在 nativePollOnce（0 意味着一定会立刻分发）
      - 新消息的 when 小于队头的 when**_&&_**当前已阻塞在 nativePollOnce（即新消息插到头部）
      - 队头是同步屏障消息**_&&_**新消息根据 when 排序是同步屏障后的第一条消息**_&&_**当前已阻塞在 nativePollOnce（同步屏障后的首个异步消息肯定是要最先被执行的）
    - 结束 nativePollOnce 后，会继续 next()的流程的取消息那一步，因为有新消息入队，所以肯定能取到一条消息，如果取到的消息的 when>now，则重新计算 nextPollTimeoutMills 阻塞，否则就直接执行分发了
  （2）死循环 2 的第二轮时，就一定能取到可用的消息，所以 next 就直接返回了，然后回到 loopOnce 开始新的一轮

那么 nativePollOnce 的作用是什么呢？下面进入 native 层的世界。

[Native ](<Handler(Native)源码解析.md>)

---

### 常见面试题补充

**Q1：Handler 导致内存泄漏？**

并非 Handler 导致内存泄漏，而是非静态内部类持有了外部类的引用导致的，在 Handler 的构造函数中，也能看到构造时进行了潜在内存泄漏的判断，如果是匿名/内部/局部类，且没有被 static 修饰，就会 log 一条提示，可能有内存泄漏。

解决方式：

- 静态内部类写成 static，如果要持有 Activity 的引用，可以用弱引用
- 用 kotlin，内部 class 自动为静态，加了 inner 才是非静态
- 不继承 Handler，直接 new 一个 Handler 对象，使用 callback 设置回调

另一种情况，延时消息可能也会导致泄漏，Activity 关闭了但是延时消息还未处理完，这种情况可以在 Activity 的 onDestroy 中 remove 所有消息。

**Q2：为什么主线程可以直接 new Handler()，子线程不行？**

可以看 Handler 的构造函数中，会取当前线程的 Looper 赋给 Handler 作为成员变量，而主线程的 looper 在整个 app 启动时的 main 方法中就 prepare 好了，因此可以直接用，但是一个新的子线程 new Handler 之前需要先把 Looper 给 prepare 了。

这就好比，你想用传送带传送货物，但传送带本身没启动。

**Q3：Handler 所发送的 Delayed 消息时间准确吗？**

Handler 所发送的 Delayed 消息时间基本准确，但不完全准确。因为多个线程去访问 MessageQueue 时，在 MessageQueue 添加或取出 Message 时都会加锁，当第一个线程还没有访问完成时，第二个线程就无法使用，实际的时间会被延迟。

Q4：\***\*主线程的 Looper 何时退出？\*\***

在 App 退出时，ActivityThread 中的 mH（Handler）收到消息`EXIT_APPLICATION`后，执行 mInitialApplication.onTerminate()和 Looper.myLooper().quit()退出。
