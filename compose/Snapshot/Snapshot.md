---
highlight: atom-one-light
theme: arknights
---

## 0 写在前面

本篇是Compose探索系列的第二篇，继上一篇SlotTable（[传送](https://juejin.cn/post/7268297948639051831)）之后，我们来看看Snapshot。

Snapshot的复杂程度会比SlotTable少上不少，但同时也更有趣！整个Snapshot系统设计最精彩的地方个人感觉是3.3节——状态的写入。其中的有关并发的设计部分、以及Record的复用机制，在我读懂代码理解过后，都感叹这样的设计实在是巧妙。推荐看看那部分！`虽然可能对日常开发没啥直接帮助，但真的可以学习思路、拓展思维！`

> `开始看之前，请确定你已经：`
>
> *   `熟练掌握kotlin的语法和Kotlin式的编程风格`
> *   `了解一些数据结构的基本知识`
> *   `能熟练使用Compose`
> 
> `当然，以上这些也可以在看文章的过程中边看边学习。`

> `如果文中的图看不清，文章最后有高清大图。 `

## 1 初识Snapshot

**Snapshot（快照）** 机制服务于Compose中的状态管理。

Compose框架之所以能感知到UI所依赖的状态的变化，就是因为Snapshot在背后默默做了很多工作。

例如，以下代码。

```kotlin
@Composable
fun HelloWorld() {
    var count by remember { mutableStateOf(0) }
}
```

这段代码中，count就是一个状态，我们可以注意到，它的创建过程包含了两部分：

1.  调用mutableStateOf()
2.  调用remember{ }

第一步，调用mutableStateOf方法，这就产生了一个状态对象；而第二步，则是调用remember方法去记住它，即，把它存到SlotTable里。

那么，我们这篇文章的主角，就是上面的第一步了。我们将深入探索Compose的状态管理系统——Snapshot系统。

根据我们Compose探索系列开篇第0节提到的方法论（[传送](https://github.com/FantasticPornTaiQiang/AndroidNote/blob/main/compose/Learning%20Navigation/Learning%20Navigation.md)），接下来的内容分为这么几部分：

1.  首先，我会直接介绍Snapshot系统的整体构成和工作原理。看完这一部分，相信你就能对Snapshot系统本身的运作一清二楚了。
2.  接下来，我们深入源码，一起探索整个系统是如何具体实现的，这一部分主要的思路是：
    1.  首先，把Snapshot系统中的各个子概念解释清楚。
    2.  然后，逐步解读其运作流程，并作总结。
3.  最后，简单介绍Snapshot与Compose的合作——更多的介绍会安排在Compose探索系列之后的文章中。

## 2 再识Snapshot

好了，上面的第一节“初识”中，我们只是对Snapshot系统有了最粗浅的了解，知道了它存在的目的和意义——就是为了状态的管理。这一节作为“再识”，我们将会对它的设计、组成、工作原理、应用场景、能解决的问题等等，作出更为细致的探讨。

现在，我们从零开始。假设我们要设计一个状态管理系统，那么有没有一个现成的、我们熟悉的状态管理系统，可以供我们参考的呢？

没错，就是我们熟悉的git系统。

利用git系统，我们可以实现多人协同工作：对于一个项目，开发者A修改其中的一部分，同时，开发者B也可以修改其中的一部分。最后工作完，开发者A和B先后把自己的更改提交合并。最终，工作完成。

上面这个场景，我们思考一下它的本质，它在多人协同工作中解决的最核心的问题是什么？

是的，就是多人能同时开发，并且最后能将所有的产出合并汇总。

现在，我们把“同时工作的多个人”当作“**同时工作的多个线程**”，而并发工作的内容变成“**对状态变量的修改**”。这是否就是我们Compose状态管理的场景？

利用一个类似git的系统，我们可以实现：

1.  多线程下的状态管理。
    *   开发者在任意线程更新状态，框架自动对状态进行合并，并反映到UI上。
    *   框架对状态合并时，也能够利用多线程并发进行，提高性能。
2.  单线程下的状态管理。
    *   就是说，即使整个团队就只有我一个人，我也可以用git，尽管它的优势不如多线程下明显就是了。

那么，接下来我们就决定用这么一个系统来实现Compose中的状态管理了，并且给它起名为Snapshot系统。

下面，我们具体展开。

这一节我们来看Snapshot系统的相关概念。

### 2.1 快照和状态

Snapshot系统中，我们把对状态的版本控制称为Snapshot，即快照。

如下图所示，现在我们全局有3个状态，count、show和name，我们在时刻1拍摄一张快照，咔嚓，生成了快照1，那么，快照1就把时刻1的状态给记录下来了。

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/e2421668eba14876ac59bcd68444d084~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1014\&h=590\&s=49712\&e=png\&b=fefefe)

现在，随着时间流逝，外界对状态进行了一些修改，我们来到了时刻2，在时刻2我们并不拍摄快照。接着，又过了一会，外界又对状态进行修改，我们来到了时刻3，在时刻3我们拍摄了第二张快照。

那么，如果我们在时刻3去访问快照1，得到的仍会是快照1中记录的状态。即，count=1，show=false，name=ptq，而并非时刻3的状态值。

**这就是快照的概念，它类似于git的提交记录。它是对状态的版本控制。**

显然，每拍摄一次快照，我们就需要对此刻的状态创建一个副本，记住它此刻的值，这样，我们才能在时刻3仍然能访问到时刻1的状态值。

那么，这个设计具体该如何实现呢？

很自然地，我们会想到在Snapshot类中添加一个自定义的“数据结构”来记录此刻所有的状态值。当拍摄快照时，我们就把此刻最新的所有状态值存入这个“数据结构”。

但很遗憾，这个想法行不通。

因为，对于我们之前提出的并发场景，假设现在各自工作完毕，生成了两个版本的状态，即两份快照，现在我们要把它们的结果合并起来。你会发现，根本无法轻易设计出这么一个“数据结构”来满足所有状态的存储。再说具体些，比如线程1的快照中新增了一个状态，而线程2的快照中修改并删除了一些状态，我们现在要合并这两个快照，就会有很多没法轻易解决的问题。比如，我们要怎么去找到这些发生变化的状态的存放位置？又该如何获取和跟踪到新增和删除的状态？如果我想获取某一个状态在不同快照中的值，以便能够对若干快照进行合并，又该怎么办？

在Snapshot类中添加一个存放所有状态的自定义的“数据结构”的这个想法，实在是太难实现了。

冷静，想想卡住的点，我们似乎是卡在所有状态的存储上了，那么，有没有什么好的办法呢？

当然！说到状态的存储，我们在上一篇文章中，专门花了大量精力搞出来一个专门用于数据存储的结构——SlotTable（[传送](https://juejin.cn/post/7268297948639051831)），我们当然应该把它利用起来！

从上一篇文章我们能知道，SlotTable不仅可以存放数据，还已经具备了组织数据的功能，即，数据的增删改查、存放位置的移动等等，这些它都帮我们实现了，甚至还已经与Composer、Composition等Compose框架的其它部分接上了轨，这不拿来用岂不是非常可惜吗？那么，留给我们的就只剩一件事了，就是版本控制。

于是，我们最终决定，把所有快照时刻的状态都存放到SlotTable里，而剩下的版本控制工作，则全权交由Snapshot系统。对于Snapshot系统来说，现在我们把它的职责剥离得非常干净——**它只负责进行版本控制**。

那么，按照我们的新实现方案，不同快照时刻的状态存储就变成了下图的样子。

![image.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/40af4b3478404d3ab0a203258a78d458~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1600\&h=906\&s=128362\&e=png\&b=fefefe)

我们不再把不同快照时刻的状态存放在各个快照的内部，而是统一放在SlotTable里。我们把每一个状态称为**StateObject（状态对象）**。我们调用的mutableStateOf()其实就创建了一个StateObject。即使之后它的值变了，它还是那个StateObject。

而我们的状态在不同快照中的值，则以**StateRecord（状态记录）** 去记录。也就是说，一个StateObject含有一个StateRecord的链表，链表的头部是最新的Record。这个StateObject的StateRecord链表就关联了不同的Snapshot，实现了我们之前说的，在多个快照时刻下所有状态值的记录功能，而且编码非常简单，各个类的职责也非常明确。

到这里为止，整个Snapshot系统的主要角色，都已经悉数登场了。

这一节我们花了非常大的篇幅去探讨清楚这些角色，以及为什么会设计出这些角色，以及这些角色的职责等等，我认为这是非常有必要的。彻底搞清楚了这些，我们才能继续往后走。

最后，小结一下，这一节叭叭了这么多，想要说明的其实就是两件重要的事：

**1. Snapshot对象本身并不存放任何状态，而只是进行版本控制，它就代表了一个版本号的概念。**

**2. 而状态本身则是以StateObject的形式存放在SlotTable中；StateObject中含有StateRecord，它是一个Record链表，记录了这个StateObject在不同快照时刻的值。** 

> `借用一句很精辟的概括：与其说是`**快照隔离了状态**`，不如说是`**状态关联了快照**。（[原文传送门](https://juejin.cn/post/7095544677515919367#heading-4)）

好了，请确保搞懂上面的两点，我们接着往下走。

> `注意：后面我们所说的状态，若无特殊说明则都是指StateObject.`

### 2.2 快照的功能

在有了2.1.1节的认知铺垫之后，接下来，我们继续思考Snapshot系统的设计。这一节我们只讲概念，基本上不看代码（`除非某些概念难以解释时，会举代码的例子来辅助说明`）。

> `注意：后文中由于我的疏忽，可写快照和可变快照这两个词存在混用现象，这里先说明，它俩是一个意思，不用管叫法的区别。`

**1、多线程**

之前提到快照系统需要能够处理多线程环境，那么，最好的方案是，利用ThreadLocal机制，保证每个线程只能同时存在一个Snapshot，这样就不会导致状态过于混乱。

**2、拍摄快照和进入快照**

接下来考虑一个线程内，一个快照的基本行为。

1.  拍摄快照（`take(Nested)Snapshot`）- 拍摄一张快照，记住当前时刻的所有状态，这之后，快照里的所有状态的值都停留在这一刻。这个操作可以相当于git操作里的从当前分支创建一个新分支。

2.  进入快照（`enter`） - 拍摄后，我们有两种选择：
    1.  不进入新的快照，那么我们就留在主线。
    2.  也可以选择进入快照，进入快照后，访问到的就是之前快照记录的状态值。


**3、可变快照和只读快照**

这个概念是为了简化和区分快照的行为。现在，我们拍摄快照并进入。

如果进入快照后，只想访问状态，而不再去修改状态，则拍摄只读快照就够了。在只读快照中尝试修改状态值，则会报错。

相对地，如果你想在快照之中修改状态值，那就得拍摄可变快照。

可变快照（`MutableSnapshot`）和只读快照（`ReadonlySnapshot`）都是Snapshot的两个实现类。

**4、嵌套快照**

当我们已经进入了一个快照，还想拍摄一个快照时，那么就可以拍摄嵌套快照。

已经处于只读快照内则只能拍摄只读的嵌套快照（`takeNestedSnapshot`），而已经处于可写快照内则可以拍摄可变和只读的嵌套快照（`takeNestedMutableSnapshot`）。

即使是嵌套快照，进入它内部后，状态也是独立隔离的。例如嵌套的可变快照中如果修改了状态值，则仅会对这一个快照可见，如果想要将更改暴露出去，那么就需要使用下面第5点的提交操作。

此外，你可能会产生一个小疑惑，就是只读的嵌套快照有什么用？反正状态不能再改变了。嵌套的只读快照，我能想到的唯一一个应用场景就是我们可以用来设置不同的监听。对于只读快照A，我们设置了状态读取的监听a，如果此时又想设置不同的监听b，那么就可以在快照A中再拍摄一个只读快照B。


**5、可变快照提交更改**

如果我们拍摄了可变快照并进入，然后在里面修改了状态值，这时，我们的更改仅仅只是在这个快照内部生效，但是对于它的父快照和子嵌套快照来说，更改都是不可见的。

如果我们想要更改生效，就需要`apply`一下，提交我们的更改。提交后，在这个快照中修改的状态就会提交并合并到父级快照。

需要注意的有两点：

1.  快照提交更改是一次性的、终止性的操作。
    这意味着，快照提交后就处于一种结束的状态，不能再次提交，也不能再创建新的嵌套快照了。

    > `但是注意：提交操作合并了状态，但状态是个对象，是引用类型。因此，当进入了一个快照并且仍未离开时，提交只是代表这个快照内的状态将对其他快照可见，但仍可以修改状态。再说白些，看下面的例子。`
    >
    > ```kotlin
    > state.value = 2
    > val snapshot = Snapshot.takeMutableSnapshot()
    > snapshot.enter {
    >     //进入快照
    >     state.value = 3
    >     snapshot.apply()
    >     state.value = 5
    >     //离开快照
    > }
    > Log.d(TAG, "test3: ${state.value}") //输出5
    > ```

2.  快照的提交是向上的、向父级的。如果当前快照A有子快照B，B有子快照C，现在B和C中都有状态改动，B先提交，修改同步到A，但是C未提交，C中的修改此时仍不可见。同时，这也意味着C中的修改永远不能生效了，因为父快照已经apply了，父快照已经处于它的非活动阶段了，C再apply也不会有用的。

**6、合并冲突**

当我们apply时，把当前修改向父级提交，但是如果父级中也发生了修改，就可能导致冲突（`就像git中那样`），具体的合并策略和合并过程我们不在这里探究，因为这一块还比较复杂，我们留到下一大节的源码部分去细究。

**7、销毁快照**

如果快照不再被需要了，一定要去销毁它（`dispose`）。你想，我们要用StateRecord记录StateObject的不同快照时刻的值，如果不去销毁，那这些值将永远存在内存中，这会造成严重的内存泄漏，且非常难以排查出来。

对于一个只读快照，我们不再需要的时候，要调用dispose，而对于可写快照，则有两种选择，可以apply也可以dispose。对可写快照来说，apply和dispose都预示着这个快照的生命周期到了最终阶段，不过硬要说的话，dispose更后一些，调用apply后还可以调用dispose，反过来则报错。

此外，对于嵌套快照，如果父快照dispose了，子快照将仍处于活动状态（`什么是活动状态就在下一点`），它也可以apply，只是，尽管不会报错，但没有作用。子快照中的状态仍然是可读的，也因此，子快照不再需要时也别忘了对它dispose。

**8、快照的生命周期**

这第8点其实相当于对前面做的一个小结，我们简单梳理一下快照的生命周期。

按照流程，完整的生命周期大致如下图所示。

<p align=center><img width="70%" src="https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/e4b836ebcf9c4185b01c0cd052b8ebf9~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=891&h=464&s=39744&e=png&b=ffffff"></p>

其中：

1.  活动期是指快照从被创建到被销毁之间的时期。
2.  上图只是展示了一个尽量完整的生命周期，但不一定所有操作都会进行，例如可能不进入而直接销毁。
3.  在可变快照中，可以先提交再销毁，也可以只提交，也可以只销毁，但不可以先销毁再提交。
4.  退出操作用了虚线图标，因为实际上我们一般使用时只调用enter函数就行了，enter函数调用完毕自然就相当于离开了。

我们再加上嵌套快照的概念，把之前提到的一些操作总结一下，下图给出了一个示例。

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/6c81d73f9dee4fa5ae66a95f22183208~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1662&h=394&s=67322&e=png&b=ffffff)

**9、全局快照**

尽管上一点中我们作了阶段性小结，但我们的新概念还没有结束，下一个概念是全局快照。

既然我们的快照都是嵌套的，那总有第一个快照吧，这个快照的父快照又是什么呢？其实我们有一个全局快照的概念，它可以理解为所有快照的最终根快照。

例如，如果当处于非嵌套的可变快照中修改状态并提交，它已经是非嵌套的快照了，没有父级快照了，那么更改将会被反映到全局快照中。

全局快照也是可变快照的一种。这也就和之前第4点中说的，只读快照内部只能拍摄嵌套的只读快照，而可变快照内部能拍摄嵌套的可变和只读快照。当我们在最开始，还没有手动创建快照时，其实，此时我们就处于全局快照中，而又由于全局快照是可变快照，所以当然可以直接拍摄可变或只读快照。

> 另外，多提一嘴。全局快照和第1点中以ThreadLocal存放的多线程的快照集合的关系：
> 
> 全局快照只是为了表示非嵌套快照的根这一概念而存在的。当我们退出非嵌套的可变快照时，自然就回到了全局快照。在第1点中我们提到过，一个线程只能有一个当前的活动快照，那么此时，当前的活动快照，即SnapshotThreadLocal.get()获取到的，就是全局快照。这就是全局快照和SnapshotThreadLocal的关系。

**10、事件的通知、监听和回调**

快照系统的最后一个概念就是事件相关的了。在快照系统中，对事件的监听以及事件触发的回调主要分为这么几类：

1. 快照内部，对状态读和写的监听：

    - 读监听（`readObserver`）：如果目前正处于当前快照或当前快照的嵌套快照中，且发生了对StateObject的值的读取操作，则触发这个回调。一般情况下，我们在拍摄快照时就传入这个回调。

    - 写监听（`writeObserver`）：如果目前正处于可变或嵌套的可变快照中，且发生了StateObject的创建，或者在首次对StateObject写入之前，则会触发这个回调。若是创建了多个嵌套快照，则可能对同一个StateObject多次触发这个回调。一般情况下，我们在拍摄快照时就传入这个回调。
    
2. 全局快照，对状态写入的监听（`registerGlobalWriteObserver`）：自最后一次调用sendApplyNotifications以来，第一次对全局快照下的StateObject写入时，触发此监听。这个监听应仅用于用来判断是否要调用sendApplyNotifications，如果监听到有状态写入，且apply了，则就要手动调用sendApplyNotifications。（`这段话的具体含义和场景我们在下面sendApplyNotifications的地方解释`）

3. 可写快照提交到全局时的回调（`registerApplyObserver`）：当可写快照的状态提交合并到全局时，触发此回调。

4. 当前线程注册状态对象的读写回调（`observe`）：它只会对当前和以后创建的此线程的快照触发读写回调，而此前的快照中的状态读写不会触发此回调。

除此以外，我们还可以主动给Snapshot系统发送通知：

1. 发送状态对象初始化通知（`notifyObjectsInitialized`）：通知快照，在此之前创建的状态对象被视为已经初始化。（`这个操作有什么使用场景，我们暂且不管。`）

2. 发送全局状态的提交通知（`sendApplyNotifications`）：在快照之外，有状态更改影响到全局快照时，需要调用这个方法给Snapshot系统发送通知。对于非嵌套的可变快照，它提交更改就会提交到全局快照，这会导致隐式调用到这个函数。（`这个操作有什么使用场景，我们也暂且不管。`）

**11、快照系统的整体结构**

好的，我们终于把快照系统的几乎所有概念给梳理完了。那么，这一小点也是总结性质的，我们来看看快照系统的整体结构。


![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/4364306a1d7e4c65aa9c12bbf37eddcc~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1428&h=576&s=58434&e=png&b=fefefe)

### 2.3 小结

小结一下，这一节我们几乎没有看代码，单纯对快照系统的设计和功能进行了详尽的分析。下一节我们将会深入源码，而如果你理解了这一节的所有概念，理解源码就会轻松不少。

此外，这一节中还有少量的使用场景和存在意义相关的内容，我们说往后稍稍、暂且不论的，我们将在第4节中再次提及它们，因为这一节如果展开介绍就太长太长了，它们的使用场景和存在意义我们将从Compose框架中窥见一番。

> `注意：后文出现的“可变快照”这个词，可能仅指可变快照本身，也可能泛指可变快照，包括嵌套的可变快照、非嵌套的可变快照和全局快照等。请在确保理解了全局快照、可变快照、嵌套快照的概念后，根据上下文语境自行区分。`

## 3 Snapshot系统的实现

这一节我们带着之前理解的概念，深入源码继续探索。

> **在这一节的开头，我们先说个题外话：**
> 源码中有很多的函数是以Locked结尾的，这都意味着，这些函数的调用必须处于sync块内，它们不是线程安全的，得加锁。
>
> 例如，对于以下代码：
> ```kotlin
> internal fun closeAndReleasePinning() {
>     sync {
>         closeLocked()
>         releasePinnedSnapshotsForCloseLocked()
>     }
> }
> ```
> 代表close和release操作的函数是标记为Locked，它们就应该被包裹在sync内，而closeAndReleasePinning函数则并没有以Locked结尾，这意味着它的调用就不需要考虑多线程问题。
>
> 所以其实，Locked后缀其实是写给调用者看的，在调用者想调用的时候去提醒他是否要注意考虑多线程问题。这是一个良好的命名习惯。（`至于为什么叫Locked（被动形式），对于带Locked后缀的函数本身来说，它自己是被sync的，所以Locked表示被锁住。`）

### 3.1 数据结构

在看Snapshot系统如何运作的源码之前，我们先打个岔。Snapshot系统中，有两个自实现的数据结构，它们是针对Snapshot系统中的某些特定的使用场景进行的优化。我们先来看看这两个数据结构。

> `如果你不想对它们有详细的了解，那么看完3.1.1和3.1.2节的前面几行介绍，知道它们是干嘛用的，就可以直接跳到3.2节了。`

#### 3.1.1 SnapshotIdSet

SnapshotIdSet是Snapshot系统中用于记录多个Snapshot的id的集合，它针对连续的id的记录做了优化。

如果你对这个数据结构本身不感兴趣，那么这一节到这里就可以跳过了，只需要知道它对多个Snapshot的id的记录作了性能优化即可，它本质上也还是个Set集合，知道这一点就足够了。

而如果你对这个数据结构感兴趣，那我们接着往下看看SnapshotIdSet这个数据结构本身。

SnapshotIdSet是一个Set，这个Set专门用于记录“位”。它记录了从第lowerBound（`即下界`）位开始，往后\[0,127]个位的值（`即0或1`）。此外，它还稀疏记录了低于lowerBound的位值。而对于lowerBound+127位以上的位，不记录。

> `说明：上面的[]表示数学中的左闭右闭区间，下同。`

什么意思呢？看图。

![SnapshotIdSet.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/862e85b57810444fa53fec44295a3a39~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1399\&h=289\&s=28059\&e=png\&b=ffffff)
这个Set的构造函数传入了四个参数：

*   `val upperSet: Long`：从lowerBound位开始，往后的 \[64,127] 位的值。
*   `val lowerSet: Long`：从lowerBound位开始，往后的 \[0,63] 位的值。
*   `val lowerBound: Int`：即下界，表示了这个SnapshotIdSet的下界是第多少位。
*   `val belowBound: IntArray?`：提供了低于lowerBound的位的稀疏访问功能。

结合图和构造函数中传入的参数，我们再解释一下。

这个结构就是对从第lowerBound位开始往后\[0,127]位提供快速访问的一个类。从lowerBound+0\~lowerBound+63位，存在lowerSet这个Long中（`一个Long是64位`），类似地，从lowerBound+64\~lowerBound+127位，存在upperSet这个Long中。这些位的值要么是0，要么是1。由于这样的存储方式，当我们访问`get`、清除`clear`和设置`set`第\[lowerBound, lowerBound+127]位时，是非常快速的，就是O(1)的复杂度。

而除此以外，对于低于lowerBound的位置，这个数据结构也提供了访问，只不过是以稀疏的方式，也就是构造函数中的belowBound数组。

> **稀疏访问**
>
> 当一个数据结构中，有大量的0值存在时，我们可以以稀疏的方式记录这个数据结构，以节省访问的时间和空间。例如，对于下面这个二维数组，当我们知道它很可能有大量0值时，我们记录它就不用全部记录了，而是只记录存在的值。
>
> ```稀疏访问演示
>      array     row col value  size 4,4
>     0 0 0 0     1   0    1
>     1 0 0 0     2   2    4
>     0 0 4 0     3   2    3
>     0 0 3 0    
>
>     //这样我们只需要记右边的row、col、对应的value、整个数组的size，
>     //就能表征这个二维数组，当数组越大、空位越多时，效果越好。
> ```
>
> 简而言之，稀疏的思想就是，当要记录的数据不多、且很稀疏时，就只记存在的值。
>
> 那么，回到我们这个Set结构里，低于lowerBound的位是很稀疏的，那么就采用这种稀疏的方式记录，而不是像高于lowerBound的连续的0\~127位一样，直接以两个Long连续地记录。

**get(bit: Int): Boolean**

访问SnapshotIdSet的第bit位（`为1则返回true`），当：

1.  bit在lowerBound往后\[0,127]位范围内，则直接取upperSet或者lowerSet这两个Long中的对应位即可。
2.  bit在lowerBound往后127位以上，则相当于访问越界，直接返回false。
3.  bit在lowerBound以下，则去belowBound中查找是否存了这个bit位，如果存了，则返回true。

对于lowerBound及以上的范围，get的复杂度都是O(1)。

而对于belowBound，get(bit)的复杂度是O(logN)，这里N是bit和lowerBound之间的差值，O(logN)是因为get方法的实现是在belowBound数组中进行二分查找，而二分查找的复杂度是O(logN)。

**clear(bit: Int): SnapshotIdSet**

这个数据结构是标记为@Imuutable的，它的构造函数的四个参数也都是val，所以这个类是不可变的。也就是说，当发生set或clear等改变它的操作时，它会创建一个新的SnapshotIdSet。

这个clear函数用于把SnapshotIdSet的某一位清空，置为0，当：

1.  bit在lowerBound往后\[0,127]位范围内，则直接把upperSet或者lowerSet这两个Long中的对应位清0即可，返回的是新的SnapshotIdSet。
2.  bit在lowerBound往后127位以上，此时，127位以上本来就被认为是0，清零自然意味着不用进行任何操作，直接返回this。
3.  bit在lowerBound以下，则去belowBound中查找是否存了这个bit位，如果存了，则构造一个新的SnapshotIdSet，新的Set的belowBound数组不包含这个位，而如果没存，同样可以理解为这一位本来就是0，那么直接返回this。
4.  此外，在上面的3种情况中，若第bit位本来就是0，或意味着bit位为0的情况，则不按上面说的操作，而是直接返回this。

那么，对于lowerBound往后位的clear操作，它的复杂度是O(1)。而对于belowBound，clear操作的代价至少是O(logN)，因为会先发生一次二分查找，而如果查找命中，则还要删除这个bit位（`构造新的belowBound数组`），则此时复杂度是O(N)。此外，clear操作对于已经为0的位，复杂度也是O(1)。

**set(bit: Int): SnapshotIdSet**

set操作与clear操作相反，是把第bit位设置为1。

与clear相同的是，由于SnapshotIdSet的不可变性，当set引起改变时，也会新构造一个SnapshotIdSet对象。

当：

1.  bit在lowerBound往后\[0,127]位范围内，与clear类似。
2.  bit在lowerBound以下，与clear类似，若第bit位不在belowBound中，就认为第bit位为0，则构造新belowBound数组和新的SnapshotIdSet对象。
3.  在上面的情况中，若bit位本就是1，或者被认为为1，则直接返回this。

而与clear不同的是，当bit位高于127位时，此时会导致lowerBound右移，出现新的lowerBound，且以此新的lowerBound构造新的SnapshotIdSet，而此时：

*   若原来的lowerSet不全为0，则原来的lowerSet中为1的位以及原来的belowBound，都会被添加到新的SnapshotIdSet的belowBound里。此外，原来的upperSet中为1的位也会被加入新的belowBound。
*   否则，原来的belowBound被舍弃，只有原来的upperSet中为1的位被加入新的belowBound。

因此，set操作的复杂度，对于bit位已经为1的位，复杂度是O(1)；对于lowerBound往后\[0,127]位的set操作，复杂度为O(1)；对于belowBound，复杂度至少为O(logN)，与clear操作类似；对于lowerBound往后127位以上，则开销会稍大。

**其他方法**

此外，这个SnapshotIdSet还提供了其它的方法，例如两个SnapshotIdSet集合之间进行位的&、&\~、|等运算。这些都是基于上面的三个方法实现的。

此外，代码中还有一个函数，叫lowestBitsOf(bits: Long)，目的是寻找bits中为1的最低位的位置。它的实现也很巧妙，用了二分查找的思想，感兴趣可以自己找源码去看看。

最后，我们再补充一点，这个SnapshotIdSet并没有实现equals方法，也就是无法比较两个SnapshotIdSet是否相同。这是故意不去实现的，因为实现的难度和代码的运行时间代价都很大，而且这个SnapshotIdSet是专用于Snapshot系统的，对于Snapshot系统来说，equals的比较是多余的、不被需要的。

好了，SnapshotIdSet这个结构就说到这里。我们现在可以总结一下了，它就是用于记录多个Snapshot的id的，这个结构对于从lowerBound往后0\~127个位置的get、set和clear都有着O(1)的访问时间复杂度，因此非常适合用于记录全局连续递增的Snapshot的id值。

#### 3.1.2 SnapshotDoubleIndexHeap

下面我们来看第二个数据结构，叫SnapshotDoubleIndexHeap。说是数据结构，这个类其实更偏向于代表一种算法。

这个类记录了一堆int值，调用lowestOrDefault方法则始终返回这些int值中的最小值。它能以O(1)的速度返回最小值。而往这个类中添加和删除int值的代价则最差情况下是O(logN)。

所以说这个类更倾向于是一种算法。

那么，Snapshot系统用这个类来跟踪所有固定快照id中的最小值。固定快照id要么是它invalid列表中的最低快照id，而当它的invalid列表为空时，则是它自己的id。

好了，固定快照是什么东西我们放在后面再去讨论，现在，与上个小节一样，我们先来看看这个SnapshotDoubleIndexHeap的本身，是怎么实现这样快速访问最小值的，对此不感兴趣的也可以跳过了。

呃，好吧，别看了，它没什么好说的，它就是一个**堆排序**，对此不熟悉的话，可以自己去搜搜堆排序的算法详解，在这里就不去花篇幅讲堆排序了。

只不过，它是在每次新增和删除int值后，都会对int堆进行调整，保证它的有序性，因此增和删最坏情况下会耗时O(logN)，而这样，访问时就可以直接访问已经处于有序状态的int堆了，因此访问最小值的开销就是访问一个数组元素的开销O(1)，没什么玄乎的。

至于这个类的名字，老长老长了，叫DoubleIndexHeap，Heap的意思是堆，表示int值构成了一个堆，采用堆排序；而DoubleIndex的意思是，为了记录这些int值的位置，还需要再用另一组IntArray对这一组values的位置进行跟踪记录，具体的我们就不再分析了。

总之这个类，或者说这个算法，就是一个堆排序的思想，最终实现了O(1)复杂度的int堆的最小值访问。

接下来我们回到主线，继续分析Snapshot系统。

### 3.2 状态的读取

从2.1和2.2两节我们知道，快照代表版本控制，状态对象可以关联不同的快照版本，从而实现了不同快照时刻下，能观察到同一状态对象的不同状态值这一功能。

这一节，我们来思考状态的访问有关内容。

#### 3.2.1 状态

先来看看StateObject和StateRecord类。

**StateObject**

```kotlin
interface StateObject {
    val firstStateRecord: StateRecord
    fun prependStateRecord(value: StateRecord)
    fun mergeRecords(
        previous: StateRecord,
        current: StateRecord,
        applied: StateRecord
    ): StateRecord? = null
}
```

1. StateObject是一个接口，2.1节提到过，StateObject内含有StateRecord，就是指这个firstStateRecord属性。StateRecord是一个链表结构，这里设计成头部永远是最新值，即调用prependStateRecord后，刚刚添加的StateRecord就是firstStateRecord。
2. 当合并时，会调用mergeRecords方法，其中三个参数分别代表：
    - previous：用于产生applied的StateRecord（`由于applied即将并入current，所以这个previous也是间接产生current的StateRecord`）。
    - current：此StateObject在父快照或者全局快照中的StateRecord。
    - applied：这次要向上提交合并的StateRecord，即将并入current。

**StateRecord**

```kotlin
abstract class StateRecord {
    internal var snapshotId: Int = currentSnapshot().id
    internal var next: StateRecord? = null
    abstract fun assign(value: StateRecord)
    abstract fun create(): StateRecord
}
```

1. StateRecord内含一个Snapshot的id，用于和Snapshot关联。
2. StateRecord有一个属性next，用于形成链表结构。
3. assign函数用于把另一个stateRecord的值赋给当前对象，create用于从当前对象创建一个新的StateRecord。

上面的属性和函数都比较简陋，我们看看StateRecord的实现类，有好几个，例如StateStateRecord、StateListStateRecord、StateMapStateRecord、ResultRecord等等，它们就分别对应我们熟悉的mutableStateOf、mutableStateListOf、mutableStateMapOf、derivedStateOf等函数。现在我们先不去看那些，先回来Snapshot系统的主线。

#### 3.2.2 版本控制

下面我们来看快照是如何具体进行对状态的版本控制的。

##### 3.2.2.1 版本控制逻辑

从前文已经知道，状态对象关联了快照的版本，那我们就可以利用这一点做文章。看下面这张图。

![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/ba3ca58021424b93b5fcba49ca2fe215~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=905&h=227&s=19133&e=png&b=ffffff)

试想，假设一开始我们处于快照版本1，创建了第一个StateRecord，值为ptq，然后，快照依次升级到版本2和3，在版本3创建了第二个StateRecord，值为ptq666，最后快照升级到版本4。

现在我们要来访问StateObject：

- 当我们处于快照1，我们能访问到的Record只有关联了id=1的Record，即ptq。
- 当我们处于快照2，此时还是只有ptq一个Record，我们只能访问到ptq。
- 当我们处于快照3，此时，我们有两个Record了，那我们访问得到的结果是？当然是关联了最新版本3的Record，即ptq666。有最新值肯定用最新值嘛。
- 当我们处于快照4，与快照3一样，我们能访问到的最新的Record还是ptq666。

现在你是否恍然大悟？总结一下，假设我们处于快照id=current时，那么我们获取StateObject的值，其实就是去获取StateRecord链表中，id不超过current的最大StateRecord记录的值。

此外，每一个快照内部，维护了一个invalid的id集合，表示对当前快照无效的快照黑名单，可访问的StateRecord关联的快照id不应位于此黑名单中。

因此，我们最终归纳一下访问StateObject的值的逻辑：**假设处于快照id=current，且它维护了一个对它无效快照集合invalid，这时我们获取StateObject的值，就是获取所有StateRecord中，id不超过current，且不在invalid集合中的最大id的StateRecord记录的值**。

此外，如果新增一条StateRecord，但可变快照还未apply时，它也是无效的，即使StateRecord的id没有超过快照id。

这里提一嘴，快照的id是按拍摄的先后顺序全局递增的，通过`nextSnapshotId`变量来分配这个全局id。全局指的是整个进程，也就是进程中所有线程所有快照共用同一套id递增体系，因此id更大的快照，也就一定意味着在时间上是后拍摄的。

最后一点，刚刚提到了invalid的id集合，那么，什么样的快照id会被认为invalid？这一小节我们暂且按下不表。

下面我们先来看看具体代码。

`fun <T : StateRecord> T.readable(state: StateObject): T `

我们可以通过调用readable方法来访问StateObject的值。比如读取通过mutableStateOf创造的SnapshotState对象时，就会调用到这个readable函数。

这个readable函数就做了两件事：
1. 触发之前2.2节第10条提及的Snapshot的readObserver监听。
2. 调用内部的readable读取状态。

<span data-id="3-2-2-1-a1" id="3-2-2-1-a1" ></span>

那我们接着看看内部的readable函数。

`private fun <T : StateRecord> readable(r: T, id: Int, invalid: SnapshotIdSet): T? `

三个参数分别为：
- `r`：StateRecord链表的起点（`链表是从新往旧链的`）。
- `id`：当前快照id。按照上一节说的，我们要找的记录的id不能大于这个id。
- `invalid`：无效id集合（`即黑名单`）。按照上一节说的，我们要找的记录的id不能位于invalid内。

这个函数的内部实现就是按照我们在3.2.2.1中提到的原则去遍历找记录而已，因此实现没有什么可看的。

**全局快照的版本控制逻辑**

接下来，我们让全局快照也一起参与讨论。

对于全局快照，它的版本控制逻辑尽管在大致逻辑上面说的一致，但需要额外注意一点：当某个可变快照还未apply时，在它之中产生的StateRecord也是无效的，不应对其它快照可见。

对于这一点，我们单独使用一个全局的`openSnapshots`集合记录所有的处于活动期，即创建了但未被应用或废弃的可变快照。除了创建可读快照以外，从全局分配新的nextSnapshotId时，都会把新id加入到openSnapshots中，这是因为既然还能产生新的id，那肯定是open的。

> `注意，对于一些全局的变量，例如nextSnapshotId、openSnapshots、applyObservers等，对其进行写操作时，都需要加sync锁。`

##### 3.2.2.2 快照版本升级

上一节提到，快照id是按拍摄的先后顺序全局递增的，id更大的快照，意味着在时间上是后拍摄的。但除此以外，还有一些操作会导致快照的版本升级。这里的版本升级指的是，快照还是同一个快照，只是单纯重新给了它一个更高的id值。

当然，快照版本升级这一概念仅出现在可变快照中，因为只有可变快照是可变的。我们这一节讨论的快照都是可变快照（`包括可变、嵌套可变、全局等`）。

升级时，快照被重新分配的id值是此时全局最大的最新id值，这也就表明了升级操作的意义，即提高权限，让当前这个快照能访问除当前id外的所有状态。

升级后，之前的快照id被记录到previousId集合，而新的id被记录到全局的openSnapshots集合，且原id+1~新id之间的id全部记录到本快照的invalid。这样操作是因为还能进行升级操作也就意味着还未apply，因此，要加入invalid集合，排除掉这些新的id，直到apply后。

previousId集合记录了这个快照对象自创建以来所有使用过的历史id，这些历史id就是由升级操作产生的，记录这些历史id是为了apply操作，这里我们先不展开。

快照的版本升级用于此快照发生变化且需要提权时，具体有以下场景：
- 拍摄嵌套快照时，先给嵌套快照分配一个全局新id，然后，再对自己升级，保证自己此时的id大于嵌套快照的id。
- 合并快照时，如果有合并的新Record产生，则也会进行快照升级，保证所有的合并记录产生最新的id。
- notifyObjectsInitialized函数实际上进行的操作就是版本升级。
- 嵌套快照apply时，会让父快照版本升级。

上面的四类场景我们后面还会详细解释，这里如果不理解的话不用着急。全局快照的版本升级我们在后面也会单独再去分析，这里只是先引出一下版本升级这个概念。

### 3.3 状态的写入

3.2节我们主要探讨了快照的id与状态的访问控制，其实也就是状态的“读”，那么这一节，我们来看看状态的“写”（`所以这节讨论的都是可变快照`）。

#### 3.3.1 固定快照

先看一个概念，固定快照。

固定快照用于决定StateObject中的StateRecord是否可以复用。

固定快照使用了一个全局变量`pinningTable`来记录，与openSnapshots等全局变量类似，对它写入也需要加sync锁。固定快照实际上是一堆整数id中的一个最小id，因此使用3.1.2节介绍的数据结构SnapshotDoubleIndexHeap来记录，该结构能直接以O(1)的速度返回一个int堆中最小的那个。

在每个快照对象创建时，都会在全局的pinningTable中更新固定快照。当快照创建时，取快照自己的id和自己的invalid中的最小者加入全局的pinningTable中。这是向pinningTable中添加id的唯一途径。

而从pinningTable中移除id的途径则比较乱，但总的来说，就是在快照dispose或abandon时会去移除id。还有，快照apply时，如果是Nested的，则会把自己创建时固定的快照id交给父快照，而如果是非Nested的，那么快照apply就意味着已经完成了所有操作，因此就会像dispose那样，移除被自己和自己的子快照固定的所有id。

上面两段文字讲了pinningTable的add和remove操作，那pinningTable是如何起作用的呢？之前已经提及，pinningTable用于决定Record是否能重用，这个判断是在状态写入时进行的，所以具体的逻辑我们在下一节状态写入中介绍。

#### 3.3.2 状态写入

对于StateObject的状态写入，最精彩的部分莫过于record的复用机制。这里代码的调用关系比较复杂，有这么几个函数都是关于状态写入的：

1. overwritable `internal`
2. writable `public`
3. writableRecord `internal`
4. newWritableRecord `internal`
5. newWritableRecordLocked `private`
6. overwritableRecord `internal`
7. newOverwritableRecordLocked `internal`
8. usedLocked `private`
9. overwriteUnusedRecordsLocked `private`

让我们来捋一捋这些函数。`由于名字太绕，后面以数字标号代替函数名。`

##### 3.3.1.1 写入的入口

我们从入口开始看。外界例如SnapshotState在写入值时会调用`①`overwritable，而例如SnapshotStateList或SnapshotStateMap在写入值时会调用`②`writable，因此前两个函数就是写入发生的入口函数了。`此外，DerivedState写入的入口直接是④，后续我们会在第4节单独分析DerivedState，现在它不是重点。`

而`①`overwritable和`②`writable的区别就是，overwritable的使用场景是当需要完全覆盖掉原record时，而writable的使用场景允许只对record部分写入，因此正好适合于List、Map等集合对象。

注意，这里的overwritable表示取到的这条record是“应该要被完全覆写的”，而非意味着“可以完全覆写、也可以只修改一部分”。再说白一点，overwritable意味着：如果写入时不完全重新覆写整个对象，就可能会导致字段不一致的问题，因为，通过overwritable取到的记录要么是已经废弃、不会再被访问的，要么就是新创建的，这意味着，如果只修改取到的record的部分字段，那么其中剩下的字段就很可能是错误的值。而，writable则意味着：允许调用者只修改其中的部分字段，只修改部分字段也是安全的，不会导致问题。这一点我们在本小节的末尾会进一步说明。([传送](#3-3-2-a1))

函数`①`的签名如下：

`fun <T : StateRecord, R> T.overwritable(state: StateObject, candidate: T, block: T.() -> R): R`

这里传入的candidate参数意为，如果当前快照id等于candidate的id，则直接使用candidate进行覆写，否则，会从state的第一条record开始寻找可以进行覆写的record，具体可以看下面`⑥`的代码。`另外，candidate在目前的源码中都是指当前快照对stateObject调用readable获取到的record。`

好，入口函数已经分析完，我们往下看。`①`和`②`会分别调用`⑥`overwritableRecord和`③`writableRecord，此外提一嘴，snapshot的writeObserver监听就是在`①`和`②`中触发的。

先来看`③`writableRecord。这个函数负责取到一条可写入的record，逻辑如下。先去3.2.2.1节末尾[传送](#3-2-2-1-a1)提到的readable函数中，尝试读取一条记录。如果读取到的记录的id就是写入时的快照id，说明这个record是在这个快照中创建的，那它当然也同样是可写的，因此直接返回它。否则调用`④`newWritableRecord去获取一条可写的record，代码如下。

```kotlin
//③
internal fun <T : StateRecord> T.writableRecord(state: StateObject, snapshot: Snapshot): T {
    val readData = readable(this, snapshot.id, snapshot.invalid) ?: readError()
    if (readData.snapshotId == snapshot.id) return readData
    return readData.newWritableRecord(state, snapshot) //调用④，之后会一路调用到⑦
}
```

好，我们继续看看`④`newWritableRecord。`④`很简单，它就是给`⑤`newWritableRecordLocked套了一个sync块，直接调用`⑤`。在`⑤`中，直接调用`⑦`得到一条record，然后直接对它进行写入。

接下来，我们先不跟进`⑦`，我们回过头去看看`⑥`。之前提到，`①`会调用到`⑥`，这之后`⑥`其实也会调用到`⑦`，殊途同归了。让我们看看`⑥`的代码。

```kotlin
//⑥
internal fun <T : StateRecord> T.overwritableRecord( 
    state: StateObject,
    snapshot: Snapshot,
    candidate: T
): T {
    if (candidate.snapshotId == snapshot.id) return candidate
    val newData = sync { newOverwritableRecordLocked(state) } //调用⑦
    newData.snapshotId = snapshot.id
    return newData
}
```

可以发现，`⑥`的代码与`③`非常类似。好，现在不论是writable还是overwritable，当需要获取一条可写的新record时，都调用到了`⑦`，那么接下来来看看`⑦`的代码。

##### 3.3.1.2 获取可用的record

```kotlin
//⑦
internal fun <T : StateRecord> T.newOverwritableRecordLocked(state: StateObject): T {
    return (usedLocked(state) as T?)?.apply {
        snapshotId = Int.MAX_VALUE
    } ?: create().apply {
        snapshotId = Int.MAX_VALUE
        this.next = state.firstStateRecord
        state.prependStateRecord(this as T)
    } as T
}
```

`⑦`的主干逻辑是，在stateObject中找一条已使用过的废弃record，如果找不到则调用create创建一条。此外，关于这里把id设为Int.MAX的目的，如果有两个线程从不同的路径调用到`⑦`，这种情况下，即使外面的调用路径加了锁也可能导致这两个线程的两路调用取到的是同一个record，除非在`⑦`函数内部再加一个锁。这里采用取巧的方式，把snapshotId设置为Int.MAX，按照我们获取snapshot的原则，当id为Int.MAX时，它永远不可能被取到，因此，在调用usedLocked或create得到一个record后，把id设为Int.MAX，可以保证它永远只会被取用一次，这样就可以在`⑦`内部少加一个锁。

现在，显然，`⑦`的内部最重要的就是函数`⑧`了，这个usedLocked就是我们的record复用机制的最终站了。上代码！

```kotlin
//⑧
private fun usedLocked(state: StateObject): StateRecord? {
    var current: StateRecord? = state.firstStateRecord
    var validRecord: StateRecord? = null
    val reuseLimit = pinningTable.lowestOrDefault(nextSnapshotId) - 1
    val invalid = SnapshotIdSet.EMPTY
    while (current != null) {
        val currentId = current.snapshotId
        if (currentId == INVALID_SNAPSHOT) {
            return current
        }
        if (valid(current, reuseLimit, invalid)) {
            if (validRecord == null) {
                validRecord = current
            } else {
                return if (current.snapshotId < validRecord.snapshotId) current else validRecord
            }
        }
        current = current.next
    }
    return null
}
```

在usedLocked中，从stateObject的第一条record开始寻找废弃的可重用的record。那么，什么样的record是废弃的、可重用的呢？

**情形一**：当record的id为INVALID_SNAPSHOT，就是废弃的、可重用的，其中INVALID_SNAPSHOT是一个常量，值为0。
- 在snapshot调用abandon时，所有被记录修改的stateObject的所有关联了这个snapshot（包括它的当前id和previousIds）的record，都会被标记为INVALID_SNAPSHOT。
- 通过调用函数`⑨`，以下情况会把record标记为INVALID_SNAPSHOT：
    - 非嵌套的可变快照apply时，所有的它自己的发生修改的stateObject和对全局修改的stateObject，都相当于是历史状态了，这些历史状态中的无效record会通过函数`⑨`被标记为INVALID_SNAPSHOT。
    - 全局快照升级时，历史的修改的record也会通过函数`⑨`被标记为INVALID_SNAPSHOT。

**情形二**：利用3.3.1节提到的固定快照机制。固定快照就是全局所有处于活动状态的快照中id最小的那一个。那么，如果说，存在一条record，它的id比固定快照的id还要小，那所有活动的快照就都将可以访问到它。那么重点来了，如果说，像这样id比固定快照还要小的record有两条呢？是不是来感觉了！！如果是这样的话，就说明，任何快照在访问这两条记录时，都一定会访问到这两条record中id更大的那一条。进而，我们得到结论，这样的两条record中，id更小的将永远不会被任何快照访问到。因此，如果存在这样的两条记录，我们就取其中id更小的作为不会再被访问到的废弃record返回。

如果不是上面的两种情况，则此函数取不到已废弃、可复用的record，需要create新的record。

如果你彻底理解了这两种情形，那么，record的写入和复用机制到这里就完全结束了，这时候再去看函数`⑧`的源码，将会非常好理解。

而对于函数`⑨`，刚刚说过，我们可以把stateObject中的过时的record标记为INVALID_SNAPSHOT。函数`⑨`内部寻找过时的record的逻辑与函数`⑧`的情形二完全一致，这里就不再去贴代码分析了。

<span data-id="3-3-2-a1" id="3-3-2-a1" ></span>

这小节的最后，我们再补充一个问题。

之前我们提及，overwritable和writable的区别是：调用overwritable就表示调用者必须对取到的record完全覆写，只修改其中一部分字段是不安全的；调用writable则意味着允许调用者只对取到的record进行部分字段修改。这是如何实现的呢？答案就在函数`⑤`newWritableRecordLocked的代码中。

```kotlin
//⑤
private fun <T : StateRecord> T.newWritableRecordLocked(state: StateObject, snapshot: Snapshot): T {
    val newData = newOverwritableRecordLocked(state)
    newData.assign(this)
    newData.snapshotId = snapshot.id
    return newData
}
```

不论是overwritable还是writable，在不能直接取到当前快照的record时，都会调用函数`⑦`来获取一条废弃的或新的快照。之前讲过，调用overwritable意味着调用者会去完全覆写这条record，因此我们就不用再操心了。而调用writable则意味着调用者想进行部分写入，因此我们在`⑤`中先帮调用者用此时传入的record值把`⑦`取到的record完全覆盖掉，这样，writable实际上取到了一条已经用当前值把废弃/新创建的record覆盖了一遍的record，那么我们之后去部分修改取到的record值，就是安全的。

##### 3.3.2.3 小结

整个3.3.2小节——状态的写入，到这里完全结束了。这一节实在是精彩，以至于我想特意为它写一个小结。

其实总结一下，整个状态写入机制的整体思想很常见，就是从stateObject的所有record中取一条当前快照能写入的，然后写入；而如果没取到，则去寻找所有record中的废弃record，然后写入；而如果没有废弃的，则新创建一条record添加到链表头部，然后写入。

这种思想是很常见的，比如RecyclerView的缓存和复用机制，也是很多级然后不断去取，最后取不到则新建；又比如Glide的缓存复用机制，也是很多级去取，取不到则去请求新资源。

但，尽管这个思想这么常见，我还是觉得这里很精彩，它其中很多思路是我没见过的，感觉很巧妙的设计思路。为了实现这样的“取废弃record”，为了判断“哪些record是废弃的”，同时还要保证高性能，Compose团队特意设计了pinningTable机制，又特意为pinningTable实现了一种数据结构算法，可谓是把优化做到了极致。

好了，不再啰嗦了，这一小节就说这些了，我们继续往下走。

#### 3.3.3 合并记录

写INVALID_SNAPSHOT注释。

### 3.4 快照的提交

2

#### 3.4.1 嵌套可变快照的提交

3

#### 3.4.2 可变快照的提交

4

### 3.5 小结一下

5

## 4 Compose与快照系统

6

### 4.1 重组与快照系统

7

### 4.2 状态与快照系统

1

#### 4.2.1 mutableStateOf

2

#### 4.2.2 mutableStateListOf

3

#### 4.2.3 derivedStateOf

4

## 5 小结

