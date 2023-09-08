## 0 写在前面

本篇是Compose探索系列的第二篇，继上一篇SlotTable（[传送](https://juejin.cn/post/7268297948639051831)）之后，我们来看看Snapshot。

Snapshot的理解难度会比SlotTable少上不少，同时也很有趣~

> `开始看之前，请确定你已经：`
> 
> - `熟练掌握kotlin的语法和Kotlin式的编程风格`
> - `了解一些数据结构的基本知识`
> - `能熟练使用Compose`
> `当然，以上这些也可以在看文章的过程中边看边学习。`
> 

> `如果文中的图看不清，文章最后有高清大图。 `
>

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
1. 调用mutableStateOf()
2. 调用remember{ }

第一步，调用mutableStateOf方法，这就产生了一个状态对象；而第二步，则是调用remember方法去记住它，即，把它存到SlotTable里。

那么，我们这篇文章的主角，就是上面的第一步了。我们将深入探索Compose的状态管理系统——Snapshot系统。

根据我们Compose探索系列开篇第0节提到的方法论（[传送](https://github.com/FantasticPornTaiQiang/AndroidNote/blob/main/compose/Learning%20Navigation/Learning%20Navigation.md)），接下来的内容分为这么几部分：
1. 首先，我会直接介绍Snapshot系统的整体构成和工作原理。看完这一部分，相信你就能对Snapshot系统本身的运作一清二楚了。
2. 接下来，我们深入源码，一起探索整个系统是如何具体实现的，这一部分主要的思路是：
   1. 首先，把Snapshot系统中的各个子概念解释清楚。
   2. 然后，逐步解读其运作流程，并作总结。
3. 最后，简单介绍Snapshot与Compose的合作——更多的介绍会安排在Compose探索系列之后的文章中。

## 2 再识Snapshot

好了，上面的第一节“初识”中，我们只是对Snapshot系统有了最粗浅的了解，知道了它存在的目的和意义——就是为了状态的管理。这一节作为“再识”，我们将会对它的设计、组成、工作原理、应用场景、能解决的问题等等，作出更为细致的探讨。

现在，我们从零开始。假设我们要设计一个状态管理系统，那么有没有一个现成的、我们熟悉的状态管理系统，可以供我们参考的呢？

没错，就是我们熟悉的git系统。

利用git系统，我们可以实现多人协同工作：对于一个项目，开发者A修改其中的一部分，同时，开发者B也可以修改其中的一部分。最后工作完，开发者A和B先后把自己的更改提交合并。最终，工作完成。

上面这个场景，我们思考一下它的本质，它在多人协同工作中解决的最核心的问题是什么？

是的，就是多人能同时开发，并且最后能将所有的产出合并汇总。

现在，我们把“同时工作的多个人”当作“**同时工作的多个线程**”，而并发工作的内容变成“**对状态变量的修改**”。这是否就是我们Compose状态管理的场景？

利用一个类似git的系统，我们可以实现：

1. 多线程下的状态管理。
   - 开发者在任意线程更新状态，框架自动对状态进行合并，并反映到UI上。
   - 框架对状态合并时，也能够利用多线程并发进行，提高性能。
2. 单线程下的状态管理。
   - 就是说，即使整个团队就只有我一个人，我也可以用git，尽管它的优势不如多线程下明显就是了。

那么，接下来我们就决定用这么一个系统来实现Compose中的状态管理了，并且给它起名为Snapshot系统。

下面，我们具体展开。

### 2.1 一些概念

这一节我们来看Snapshot系统的相关概念。

#### 2.1.1 快照和状态

Snapshot系统中，我们把对状态的版本控制称为Snapshot，即快照。

如下图所示，现在我们全局有3个状态，count、show和name，我们在时刻1拍摄一张快照，咔嚓，生成了快照1，那么，快照1就把时刻1的状态给记录下来了。

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/e2421668eba14876ac59bcd68444d084~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1014&h=590&s=49712&e=png&b=fefefe)

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

![image.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/40af4b3478404d3ab0a203258a78d458~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1600&h=906&s=128362&e=png&b=fefefe)

我们不再把不同快照时刻的状态存放在各个快照的内部，而是统一放在SlotTable里。我们把每一个状态称为**StateObject（状态对象）**。我们调用的mutableStateOf()其实就创建了一个StateObject。即使之后它的值变了，它还是那个StateObject。

而我们的状态在不同快照中的值，则以**StateRecord（状态记录）** 去记录。也就是说，一个StateObject含有一个StateRecord的链表，链表的头部是最新的Record。这个StateObject的StateRecord链表就关联了不同的Snapshot，实现了我们之前说的，在多个快照时刻下所有状态值的记录功能，而且编码非常简单，各个类的职责也非常明确。

到这里为止，整个Snapshot系统的主要角色，都已经悉数登场了。

这一节我们花了非常大的篇幅去探讨清楚这些角色，以及为什么会设计出这些角色，以及这些角色的职责等等，我认为这是非常有必要的。彻底搞清楚了这些，我们才能继续往后走。

最后，小结一下，这一节叭叭了这么多，想要说明的其实就是两件重要的事：

**1. Snapshot对象本身并不存放任何状态，而只是进行版本控制，它就代表了一个版本号的概念。**

**2. 而状态本身则是以StateObject的形式存放在SlotTable中；StateObject中含有StateRecord，它是一个Record链表，记录了这个StateObject在不同快照时刻的值。**

> `借用一句很精辟的概括：与其说是`**快照隔离了状态**`，不如说是`**状态关联了快照**。（[原文传送门](https://juejin.cn/post/7095544677515919367#heading-4)）

好了，请确保搞懂上面的两点，我们接着往下走。

#### 2.1.2 快照的功能

在有了2.1.1节的认知铺垫之后，接下来，我们继续思考Snapshot系统的设计。

这一小节，我们来看看快照具体有哪些能做的事。


## 3 深入源码

### 3.1 数据结构

Snapshot系统里有两种自己实现的数据结构，它们是针对Snapshot系统中的某些特定的使用场景进行的优化。我们先来看看这两个数据结构。

#### 3.1.1 SnapshotIdSet

SnapshotIdSet是Snapshot系统中用于记录多个Snapshot的id的集合，它针对连续的id的记录做了优化。

如果你对这个数据结构本身不感兴趣，那么这一节到这里就可以跳过了，只需要知道它对多个Snapshot的id的记录作了性能优化即可，它本质上也还是个Set集合，知道这一点就足够了。

而如果你对这个数据结构感兴趣，那我们接着往下看看SnapshotIdSet这个数据结构本身。

SnapshotIdSet是一个Set，这个Set专门用于记录“位”。它记录了从第lowerBound（`即下界`）位开始，往后\[0,127]个位的值（`即0或1`）。此外，它还稀疏记录了低于lowerBound的位值。而对于lowerBound+127位以上的位，不记录。

> `说明：上面的[]表示数学中的左闭右闭区间，下同。`

什么意思呢？看图。

![SnapshotIdSet.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/862e85b57810444fa53fec44295a3a39~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1399&h=289&s=28059&e=png&b=ffffff)
这个Set的构造函数传入了四个参数：

*   `val upperSet: Long`：从lowerBound位开始，往后的 \[64,127] 位的值。
*   `val lowerSet: Long`：从lowerBound位开始，往后的 \[0,63] 位的值。
*   `val lowerBound: Int`：即下界，表示了这个SnapshotIdSet的下界是第多少位。
*   `val belowBound: IntArray?`：提供了低于lowerBound的位的稀疏访问功能。

结合图和构造函数中传入的参数，我们再解释一下。

这个结构就是对从第lowerBound位开始往后\[0,127]位提供快速访问的一个类。从lowerBound+0\~lowerBound+63位，存在lowerSet这个Long中（`一个Long是64位`），类似地，从lowerBound+64\~lowerBound+127位，存在upperSet这个Long中。这些位的值要么是0，要么是1。由于这样的存储方式，当我们访问（`get`）、清除（`clear`）和设置（`set`）第\[lowerBound, lowerBound+127]位时，是非常快速的，就是O(1)的复杂度。

而除此以外，对于低于lowerBound的位置，这个数据结构也提供了访问，只不过是以稀疏的方式，也就是构造函数中的belowBound数组。

> **稀疏访问**
> 
> 当一个数据结构中，有大量的0值存在时，我们可以以稀疏的方式记录这个数据结构，以节省访问的时间和空间。例如，对于下面这个二维数组，当我们知道它很可能有大量0值时，我们记录它就不用全部记录了，而是只记录存在的值。
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
> 简而言之，稀疏的思想就是，当要记录的数据不多、且很稀疏时，就只记存在的值。
> 
> 那么，回到我们这个Set结构里，低于lowerBound的位是很稀疏的，那么就采用这种稀疏的方式记录，而不是像高于lowerBound的连续的0\~127位一样，直接以两个Long连续地记录。

**get(bit: Int): Boolean**

访问SnapshotIdSet的第bit位（`为1则返回true`），当：
1. bit在lowerBound往后[0,127]位范围内，则直接取upperSet或者lowerSet这两个Long中的对应位即可。
2. bit在lowerBound往后127位以上，则相当于访问越界，直接返回false。
3. bit在lowerBound以下，则去belowBound中查找是否存了这个bit位，如果存了，则返回true。

对于lowerBound及以上的范围，get的复杂度都是O(1)。

而对于belowBound，get(bit)的复杂度是O(logN)，这里N是bit和lowerBound之间的差值，O(logN)是因为get方法的实现是在belowBound数组中进行二分查找，而二分查找的复杂度是O(logN)。

**clear(bit: Int): SnapshotIdSet**

这个数据结构是标记为@Imuutable的，它的构造函数的四个参数也都是val，所以这个类是不可变的。也就是说，当发生set或clear等改变它的操作时，它会创建一个新的SnapshotIdSet。

这个clear函数用于把SnapshotIdSet的某一位清空，置为0，当：
1. bit在lowerBound往后[0,127]位范围内，则直接把upperSet或者lowerSet这两个Long中的对应位清0即可，返回的是新的SnapshotIdSet。
2. bit在lowerBound往后127位以上，此时，127位以上本来就被认为是0，清零自然意味着不用进行任何操作，直接返回this。
3. bit在lowerBound以下，则去belowBound中查找是否存了这个bit位，如果存了，则构造一个新的SnapshotIdSet，新的Set的belowBound数组不包含这个位，而如果没存，同样可以理解为这一位本来就是0，那么直接返回this。
4. 此外，在上面的3种情况中，若第bit位本来就是0，或意味着bit位为0的情况，则不按上面说的操作，而是直接返回this。

那么，对于lowerBound往后位的clear操作，它的复杂度是O(1)。而对于belowBound，clear操作的代价至少是O(logN)，因为会先发生一次二分查找，而如果查找命中，则还要删除这个bit位（`构造新的belowBound数组`），则此时复杂度是O(N)。此外，clear操作对于已经为0的位，复杂度也是O(1)。

**set(bit: Int): SnapshotIdSet**

set操作与clear操作相反，是把第bit位设置为1。

与clear相同的是，由于SnapshotIdSet的不可变性，当set引起改变时，也会新构造一个SnapshotIdSet对象。

当：
1. bit在lowerBound往后[0,127]位范围内，与clear类似。
2. bit在lowerBound以下，与clear类似，若第bit位不在belowBound中，就认为第bit位为0，则构造新belowBound数组和新的SnapshotIdSet对象。
3. 在上面的情况中，若bit位本就是1，或者被认为为1，则直接返回this。

而与clear不同的是，当bit位高于127位时，此时会导致lowerBound右移，出现新的lowerBound，且以此新的lowerBound构造新的SnapshotIdSet，而此时：
- 若原来的lowerSet不全为0，则原来的lowerSet中为1的位以及原来的belowBound，都会被添加到新的SnapshotIdSet的belowBound里。此外，原来的upperSet中为1的位也会被加入新的belowBound。
- 否则，原来的belowBound被舍弃，只有原来的upperSet中为1的位被加入新的belowBound。

因此，set操作的复杂度，对于bit位已经为1的位，复杂度是O(1)；对于lowerBound往后[0,127]位的set操作，复杂度为O(1)；对于belowBound，复杂度至少为O(logN)，与clear操作类似；对于lowerBound往后127位以上，则开销会稍大。

**其他方法**

此外，这个SnapshotIdSet还提供了其它的方法，例如两个SnapshotIdSet集合之间进行位的&、&~、|等运算。这些都是基于上面的三个方法实现的。

此外，代码中还有一个函数，叫lowestBitsOf(bits: Long)，目的是寻找bits中为1的最低位的位置。它的实现也很巧妙，用了二分查找的思想，感兴趣可以自己找源码去看看。

最后，我们再补充一点，这个SnapshotIdSet并没有实现equals方法，也就是无法比较两个SnapshotIdSet是否相同。这是故意不去实现的，因为实现的难度和代码的运行时间代价都很大，而且这个SnapshotIdSet是专用于Snapshot系统的，对于Snapshot系统来说，equals的比较是多余的、不被需要的。

好了，SnapshotIdSet这个结构就说到这里。我们现在可以总结一下了，它就是用于记录多个Snapshot的id的，这个结构对于从lowerBound往后0~127个位置的get、set和clear都有着O(1)的访问时间复杂度，因此非常适合用于记录全局连续递增的Snapshot的id值。

#### 3.1.2 SnapshotDoubleIndexHeap

下面我们来看第二个数据结构，叫SnapshotDoubleIndexHeap。说是数据结构，这个类其实更偏向于代表一种算法。

这个类记录了一堆int值，调用lowestOrDefault方法则始终返回这些int值中的最小值。它能以O(1)的速度返回最小值。而往这个类中添加和删除int值的代价则最差情况下是O(logN)。

所以说这个类更倾向于是一种算法。

那么，Snapshot系统用这个类来跟踪所有固定快照id中的最小值。固定快照id要么是它invalid列表中的最低快照id，而当它的invalid列表为空时，则是它自己的id。

好了，固定快照是什么东西我们放在后面再去讨论，现在，与上个小节一样，我们先来看看这个SnapshotDoubleIndexHeap的本身，是怎么实现这样快速访问最小值的，对此不感兴趣的也可以跳过了。

呃，好吧，别看了，它没什么好说的，它就是一个**堆排序**，对此不熟悉的话，可以自己去搜搜堆排序的算法详解，我们在这里就不去花篇幅讲堆排序了。

只不过，它是在每次新增和删除int值后，都会对int堆进行调整，保证它的有序性，因此增和删最坏情况下会耗时O(logN)，而这样，访问时就可以直接访问已经处于有序状态的int堆了，因此访问最小值的开销就是访问一个数组元素的开销O(1)，没什么玄乎的。

至于这个类的名字，老长老长了，叫DoubleIndexHeap，Heap的意思是堆，表示int值构成了一个堆，采用堆排序；而DoubleIndex的意思是，为了记录这些int值的位置，还需要再用另一组IntArray对这一组values的位置进行跟踪记录，具体的我们就不再分析了。

总之这个类，或者说这个算法，就是一个堆排序的思想，最终实现了O(1)复杂度的最小int值访问。

接下来我们回到主线，继续分析Snapshot系统。