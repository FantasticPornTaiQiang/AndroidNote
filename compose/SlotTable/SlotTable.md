---
theme: arknights
highlight: atom-one-light
---

## 写在开头

> `SlotTable是Compose的核心之一，这篇文章尝试着在资料极少、完全不懂的情况下去探索一下SlotTable相关的内容。因此，本篇文章会非常注重思路，在阅读过程中，会有非常多的关于“思考过程”的部分。如果你想直接看结论性的东西，那么请自己找小标题跳着阅读。`
>
> `另外，看这个源码纯属好奇好玩，对实际应用Compose几乎没有帮助。`

> `开始看之前，请确定你已经：`
>
> - `熟练掌握kotlin的语法和Kotlin式的编程风格（当然，也可以在看文章的过程中边看边学习）`
> - `了解一些数据结构的基本知识`
> - `能熟练使用Compose`
> - `有一定的英语基础（因为源码的变量命名和注释都是英文，如果大面积不知道单词是什么意思，在代码的理解上会有一定困难的）`

> 如果文中的图看不清，这里有[高清大图](#4-anchor1)。

## 0 初识 SlotTable

可能你完全没听过这个 SlotTable，所以我们先来大概认识一下它。

SlotTable 是用于 Compose 实际存放各种“数据”的结构。我们可以通过 currentComposer.compositionData 取到 CompositionData 接口的对象，它实际上就是个 SlotTable。

好，现在你对 SlotTable 已经有了一个最模糊的认识了，相信此时你肯定会有各种各样的疑问：

- 从全局来看，假如我在 Activity 的 onCreate 调用 setContent{}构造了一个 Compose 编写的 Activity，那么它里面含有几个 SlotTable？
- 既然之前说 SlotTable 是存放“各种”数据的，那它的内部到底存放了哪些类型的数据？
- 它的内部结构是怎么样的，它是如何存放数据的？
- Compose 编写页面的方式非常灵活，可以随意地进行条件判断或者循环，例如以下代码：
  ```kotlin
     @Composable
     fun test() {
         if (condition) {
             Text("ptq")
         }
     }
  ```
  那么，例如这种条件控制的@Composable 调用，会影响 SlotTable 的数据结构吗？
  - 进一步地，什么时候、谁、如何导致 SlotTable 的数据发生变化？
  - 再进一步地，Compose 作为一个 UI 框架，如果是复杂的 UI 页面，如何保证数据变更的高性能？

初次接触 SlotTable，大概能产生上面这些疑问，就让这些疑问来驱动我们去继续往下深入探索吧。

## 1 SlotTable 的结构

要想弄清楚 SlotTable 机制，首先，肯定是要搞清楚它的结构。

### 1.1 初识

那么接下来，我们开始探索 SlotTable 的结构。首先来想办法瞄一眼这个 SlotTable，看下面的代码。

```kotlin
@Composable
fun Greeting() {
    var show by remember { mutableStateOf(false) }
    val composition = currentComposer.composition

    Button(onClick = { show = !show }) {
        if (show) {
            Text("show")
        }
    }

    LaunchedEffect(show) {
        launch {
            delay(500)
            composition.printSlotTable()
        }
    }
}
```

printSlotTable 是我自己写的一个扩展函数，就是利用反射把 SlotTable 打印出来，点进 SlotTable 的源码一看，发现他有三千多行，SlotTable 类的属性也是五花八门的，怎么办？我定睛一看，发现里面有个叫`asString`的函数，它是 Compose 团队用来调试 SlotTable 的 dump 函数，而且注释还说不要直接 toString，因为既耗时又内容繁杂。那么这个 asString 就最适合我们用来大致了解 SlotTable 了，来看看它输出了啥。

![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/447ff07ae2cd4bfe88ebadf7f8b0e57c~tplv-k3u1fbpfcp-watermark.image?)
从这个输出，我们能得到以下推测：

1.  一个 SlotTable 是由若干个 Group 组成的
2.  从 Group 前的留白可以推出，SlotTable 是一个树状结构
3.  从我随便圈的一些地方可以看出，这个 SlotTable 还真是存放了“各种”数据，有我们的 remember（mutableState），有 LaunchedEffect，有 CompositionLocal 等等，还有各种 lambdaImpl
4.  还有几个值得关注的字段：
    - aux - 字面意思应该是辅助数据，可以看到 remember、LaunchEffect 之类的老朋友
    - slots - 字面意思是插槽，从这个命名来看，这应该是从属于 Group 的更小的数据结构，它应该是记录了实际的数据，例如 remember 作为辅助信息记录在 aux 中，而 remember 的 mutableState 就放在 slots 里

暂时就能推出这些信息，那么，现在我们有了一个大概的认知之后，过家家结束了。接下来就要开始盘一盘这 SlotTable 的 3000 多行代码了。

### 1.2 结构

先来看看 SlotTable 的属性和概念。

> `这些属性、概念在SlotTable.kt的开头有一大段注释解释，但是直接看这些注释，我一开始并没有理解，甚至看得很绕，因此，我们不能操之过急，先来探索一些信息。`

#### 1.2.1 groups: IntArray

在 1.1 节中我们知道了 SlotTable 由 Group 构成，而正好，SlotTable 类的第一个属性就是 groups，只不过，它看起来是一个 int 数组，而非我们之前想的一棵树？非也。它是以数组来表示的一棵树，一般情况下这么干是为了性能。

由于 groups 不是一个专门的 Group 类的 array，而是一个 IntArray，那么单个 group 肯定也是以 int 表示的了。下面看看单个的 group。

> `也就是说，如果正常来编写这个Group的代码，我们是会定义一个Group Class，然后所有的group对象存放在Group[]数组中，但是，Group应该会是使用上很频繁、实例数量很多的一个类，因而，为了性能，设计者采用了int的方式来表征Group类的字段，而Group本身也不再以一个专门的类来存储，而是由连续存放的若干int值组成。`

##### 1.2.1.1 Group

一个 group 由 5 个 int 字段构成，分别为 key、groupInfo、parentAnchor、size、dataAnchor。

- 至于这些字段是什么意思，我们现在能大概猜测一下：
  - key - 用于标识这个 group
  - groupInfo - 用于记录 group 本身的信息？
  - parentAnchor - 父节点的锚点？锚点就是位置的意思，也就是说记录了父 group 在整个 groups 数组中的位置？
  - size - group 的大小？
  - dataAnchor - 关于 group 内的实际数据存放的位置信息？（`也就是说，groups应该只是存放group本身信息的数组，而实际存放group里面的数据——也就是slots的地方应该在另一处，所以后续需要访问某个group的具体数据时，应该是先在groups数组里查到dataAnchor的信息，再根据这个dataAnchor索引去另一处访问具体数据`）

由于全是 int 存储，我们访问 group 的内容是非常不便的，从注释可以知道，已经写好了一堆扩展函数来方便地访问 group 的字段，我们随便找几个例子看看。

```kotlin
//根据address获取groupInfo
private fun IntArray.groupInfo(address: Int): Int =
    this[address * Group_Fields_Size + GroupInfo_Offset]

//这个group是否有aux信息
private fun IntArray.hasAux(address: Int) =
    this[address * Group_Fields_Size + GroupInfo_Offset] and Aux_Mask != 0

```

我们先不看上面这段具体的代码，注意到出现了几个大写的变量，实际上他们是一些定义，这些定义对于我们去了解结构是非常有帮助的。

> **关于组的常量**
>
> - 组的构成
>   ![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/52708a9821984d97a0e88152e9bd7367~tplv-k3u1fbpfcp-watermark.image?)
>
>   1.  key 就是 startGroup 中传入的唯一 key
>   2.  就和我们之前说的一样，一个组用 5 个 int 表示，而例如 groupInfo 这种，明显是一个 int 放不下的，因此这里又采用了我们熟悉的位运算来存储信息。
>
> - GroupInfo 的构成（共占 1 个 int，32 位）
>   ![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/8b77f8b44d1d42bb9a2179bbfb5be3fe~tplv-k3u1fbpfcp-watermark.image?)
>
>   各个字段的含义
>
>   - n：为 1 则这个 group 代表一个 node
>   - ks：为 1 则这个 group 有一个 object key slot
>   - ds：为 1 则这个 group 有一个 aux slot
>   - m：为 1 则这个 group 被标记
>   - cm：为 1 则这个 group 有一个标记
>   - 其他低位全代表 node count
>
>   `至于这些字段是什么意思，暂且先不管，凭自己的认知先猜猜，有个大概的印象就行，我们往下看。`

##### 1.2.1.2 寻址

好，继续回来，到这里为止，我们的任务是解读访问 Group 字段的代码，**为了真正能搞懂 Group 乃至后文 SlotTable 中各种操作相关的代码**，我们还需要再理解一个东西，就是**寻址**。

SlotTable 的数组访问的设计中，有四个重要概念——Index、Address、Anchor 和 Gap。

Index 和 Address 看起来比较好理解，应该都是索引位置的意思，Anchor 是锚点的意思，应该也是类似于索引位置，但是带有“标记”这层含义。而 Gap 意为间隙。

由于 group 在 groups 数组中的插入移动删除等操作，可能会导致 groups 数组中 group 与 group 之间产生间隙，这个间隙就是 gap，它本质上是一段连续的数组区间，存放的值全是 null。

那么，这四个角色的关系是什么呢？我通过阅读源码，总结出了下面这张图。这张图非常重要！它是后面正确理解代码的基础。

<p align=center><img src="https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/d1b41667fd424e5f88ed78188ee0d4af~tplv-k3u1fbpfcp-watermark.image?" alt="image.png"  /></p>

我们以 groups 数组为例，假设有 100 个 group，gapLen=2。

- 可以看到，Index 和 Address 是两个假想的序列——“假想”的意思是他们并不存在于物理内存中。真正存在物理内存中的序列只有 groups。

- 从这里就可以看出，对于 100 个 group，Index 序列有 100 个，它的容量（capacity）就是 100.也就是说，Index 是不考虑 gap 的。

- 而对于 Address 序列，它是考虑了 gap 在内的，因此当 gapLen=2 时，Address 序列的 size 是 102，其中有 2 个是 gap。

- gapStart 这个变量记录了 gap 开始的索引位置（`算法保证了整个groups中只有一段连续的gap`），即 gapStart=4。注意，gapStart 记录的是 Address，而非 Index。

- groups 则是实际存在的序列。它对应的实际上是 Address，也就是说，groups 也是包含 gap 在内的。每个 Address 对应 groups 中的 5 个元素，包括 Address 序列中的 gap，也是对应 5 个元素的。因此 groups.size=size\*5 而非 capacity\*5，另外，有些代码中会见到 physical address 的概念，指的就是 groups 中的单个元素。

- 最后要说的就是 Anchor 了。Anchor 是基于 Index 得出来的一个值。
  - 当 Anchor 插入在 gap 之前（含 gapStart 位置）时，它表示的是它到数组开头的距离。
    - 例如图中的 Anchor1，值为 2.
    - 例如图中的 Anchor2，值为 4.
  - 当 Anchor 插入在 gap 之后时，它表示的是它到数组末端的距离。此时会用负数来标记它是在 gap 后面。（`负数仅作标记作用`）
    - 例如图中的 Anchor3，值为-94.

在你确定已经完全理解上图后，我们往下走。

从上图可以看出，Address 相关的计算是不用管 gap 的。意思是，在通过 address 获取 groups 数组中的 group 时，直接用 address \* Group_Fields_Size 即可，而不需要再进行 gap 相关的计算。原因就是 Address 这个概念已经把 gap 考虑在内了，Address 序列对应的就是真实存在的 groups 数组。

上图的最后列出了三个计算公式。分别是 Index 与 Address 的互转、根据 Index 插 Anchor、取 Anchor 对应的 Index。

> `提醒一点：源码中不同地方的capacity和size存在混用的现象，需要自行辨别capacity或者size指什么，图中标注的capacity和size仅仅只是为了对应转换公式中的代码。`

##### 1.2.1.3 访问 Group 的字段

好，在 1.2.1.1 的 Group 字段讲解和 1.2.1.2 的寻址讲解之后，我们终于可以看懂 1.2.1.1 的那段访问 Group 字段的代码了。

```kotlin
//根据address去groups数组中获取groupInfo
private fun IntArray.groupInfo(address: Int): Int =
    this[address * Group_Fields_Size + GroupInfo_Offset]

//根据address去groups数组中确定这个group是否有aux信息
private fun IntArray.hasAux(address: Int) =
    this[address * Group_Fields_Size + GroupInfo_Offset] and Aux_Mask != 0

```

第一个函数就是，通过 address 找 groupInfo，对于 address 的计算，我们不考虑 gap，直接乘以 Group_Fields_Size，也就是 5，然后再加上 groupInfo 对应的 offset，也就是 1，即可取到对应的 groupInfo 了。

第二个函数就是，判断这个 group 是否有 aux 信息，那么取到对应 groupInfo 的 int 值后，和第 28 位做与运算，校验是否为 1 即可。

那么这里仅仅举了两个函数的例子，对其他 group 相关的扩展函数也都类似地看看，我们能一步步推知关于 group 结构的更多信息。

---

有必要提一嘴的是 objectKeyIndex、auxIndex、slotAnchor 这几个函数：

```kotlin
private fun IntArray.objectKeyIndex(address: Int) = (address * Group_Fields_Size).let { slot ->
    this[slot + DataAnchor_Offset] +
        countOneBits(this[slot + GroupInfo_Offset] shr (ObjectKey_Shift + 1))
}
```

看看它的实现，this\[address \* Group_Fields_Size]取到了这个 group，但为什么它的 objectKey 的 Index 还要再加上 countOneBits(this\[slot + GroupInfo_Offset] shr (ObjectKey_Shift + 1))这一段呢？

countOneBits 函数返回了 0-7 的二进制表示中，“1”位的个数，而 ObjectKey_Shift=29，对照上面的 GroupInfo 构成表看，ObjectKey_Shift + 1 = 30 代表着 node 的位置。如果 groupInfo >> 30，那也就只剩 31 和 30 两位了，而 31 位是 0，那实际上，加上的这一大坨，实际上就是在加上可能存在的 node 导致的索引偏移——node 不存在则是 0，存在则 1。

这整个函数的作用是获取 object key 在另一个数组（slots）中的索引位置。从上面的分析就可以看出，group 的 objectKey 实际存放在这个 group 对应的 slots 中的第二个位置（第一个位置是 node，且它们都是可选的）。

而 auxIndex、slotAnchor(data)的索引获取函数也非常类似，他们依次存放于 slots 的第三个，第四个位置——当然，aux 也是可选的。

这个位运算的设计挺巧妙的，group 的 groupInfo 中，相应的“1”位代表 node、objectKey、aux 是否存在，而由于它们是可选的，“1”位的个数就反映了 slots 数组中真正的数据 slot（即 slotAnchor/data slot）开始的位置。

> `这一部分可以结合下面1.2.1.4节的图来理解。`

##### 1.2.1.4 目前可以获得的情报

那么，到目前为止，我们都是没有看 SlotTable.kt 开头的那段注释的，那一大段注释，初次看起来可能会非常不知所云，但是，经过我们之前的分析，我们已经掌握了一些信息，现在我们再回过头去看它。

> `下面的内容并不是直接对注释的翻译，而是带上了我的理解，可以结合底下的图来辅助理解。`

- `Address`
  - `Group`在 groups 数组中的索引，关于 Address 的计算不需要再处理 gap，因为它本身已经包含 gap 了。
- `Anchor`
  - 它是基于`Index`的包装值，只不过换了一个称呼：锚点——它的值不会随着 groups 或者 slots 数组中被插入或者删除元素而改变，所以叫做锚点。（`至于为什么不会变，先不急，后面揭晓`）
  - 如果锚点的位置在 gap 之前，则它的值是正数，反之是负数。
  - 如果锚点值为负数，则它记录了从数组末端到它的距离。
  - 如果 slots 或者 groups 数组有新增或者删除，这个距离不会变，但它能自动反映出删除和插入操作。（`只要对比Address序列和Index序列就可以发现插入/删除`）
  - 锚点值唯一会发生变化的情况是：gap 的移动导致了`Group`或`Slot`的`Address`移动时。
  - 锚点这个术语并不只是用于 Anchor 这个类，例如在`Group的字段`中，我们之前已经见过了 parent Anchor、data Anchor 等术语，它们也是一样的，只不过它们以直接的 int 存放于`Group的字段`中，而非 Anchor 类（`其实Anchor类就是对Index这个int简单封装了一下`）。
- `Aux`
  - 辅助数据，它们可以与 node 产生关联，并且独立于`Group`的 slots 之外——之前我们的分析在这段注释中就得到了印证，在 1.2.1.3 节的最后，我们分析到 slots 数组中的前几个位置，可能存放`Node`、`ObjectKey`、`Aux`等数据，且它们与`Data`数据一并存放在 slots 数组中。
  - 使用场景，例如 composer 使用它来记录 CompositionLocal 映射，因为在组启动时，映射是未知的，只有在使用任意数量的 slots 之后计算映射时才知道。（`这段话看不懂也没关系，CompositionLocal不是本文的内容，我们只讨论SlotTable，这里就知道它有个使用场景就行了`）
- `Data`
  - 之前已经提过了，一个`Group`的 slots 中，有可选的`Node`、`ObjectKey`、`Aux`等“辅助”数据，那么其后的`Data`就是“正式”数据了。
- `Group的字段`
  - 之前也已经细说，这里就不再详细解释了，由于开销原因，Group 并没有用类去定义，而是直接以多个 int 值的形式定义，包括 Key、GroupInfo、Parent Anchor、Size、Data Anchor 等，而其中 GroupInfo 又包括了`Node`、`ObjectKey`、`Aux`等是否存在，以及，如果存在的话，它们在 slots 中存放的位置等信息，此外，GroupInfo 还有 node count 信息。
  - 另外注意，Group 的 Size 是指子 Group 的数量。Size 是不包括自己的。
- `Group`
  - 之前已经提及，一个`Group`就是 groups 中 5 个连续存放的 int。
  - `Group`是一个树状结构，可以包含子`Group`。
  - 由于在 slots 数组中，数据是连续存放的，因此，`Group`中的信息可以用来描述如何来解释 slots。换言之，我们可以通过 Group 中记录的索引信息，去 slots 中找这个 group 对应的具体数据——在 1.2.1.3 节我们已经亲自分析了这些函数。
  - `Group`有一个 int 类型的 key，还有可选的`Node`、`ObjectKey`、`Aux`，然后还有 0 个或多个 data slot。
    <span id="1-2-1-4-anchor1"/>
  - `Group`实际上是一个树结构，它的子`Group`在 groups 数组中的存放位置就位于它自己后面。
  - 这种数据结构有利于对子`Group`进行线性扫描。
  - 除非通过`Group`相关的`Anchor`，否则随机访问是昂贵的。
- `Index`
  - `Index`是 groups 数组或者 slots 数组中用于标识`Group`或者`Slot`的逻辑索引。这个索引不会随着 gap 移动而变化（因为本身就已经把 gap 排除在外了）。
  - 如果 gap 在末尾，则`Group`或`Slot`的`Index`值和`Address`值是相同的。这在 SlotReader 中得到了充分利用。为了 SlotReader 的简单性和效率，gap 总是移动到最后，导致 SlotReader 中的`Index`值和`Address`值相同。
  - 代码中的所有涉及 array index 的字段，都是指的`Index`值，而非`Address`值，除非它们命名上显式以 Address 结尾。
  - 暴露出去的 API 提到的索引都是指`Index`值，而非`Address`值。`Address`值是 SlotTable.kt 内部的。
- `Key`
  - 用以唯一标识一个 Group，是一个 int 值，由 Compose 编译器产生，startGroup 函数传入。
- `Node`
  - 与`ObjectKey`、`Aux`等类似，它也是属于`Group`的一个辅助数据，独立于`Group`的 data slot。
  - 使用场景，例如，当使用 UIEmitter 发射时，LayoutNode 会被存在 node slot 中。（`同样，这段话看不懂也没关系，UIEmitter不是本文的内容，我们只讨论SlotTable，这里就知道它有个使用场景就行了`）
- `ObjectKey`
  - 除 int 类型的 Key 以外，每个`Group`还有一个辅助的 Object(Any)类型的`Key`。
  - 使用场景，例如，调用 key()这个@Composable 函数，会产生一个`ObjectKey`，可以自行去了解一下 key()这个 Composable 的使用场景。
- `Slot`
  - slots 数组中的最小单位，一个就是 slots 数组的一个元素。之前提到的`Node`、`ObjectKey`、`Aux`等辅助数据和`Group`的 data slot 等正式数据，都存在`Slot`里——前三者各占一个 Slot（如果存在的话），后者占 0 个或多个`Slot`。

OK，终于结束了，上面就是 SlotTable 中的绝大部分概念了，我画了一张图，总结一下。

<p align=center><img width="100%" src="https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/10127ba1333f4f17b342ba2d8bec567e~tplv-k3u1fbpfcp-watermark.image?"></p>

至此，我们通过读 SlotTable.kt 的代码和注释并分析的方式，把 SlotTable 的大部分概念和结构给弄明白了，这部分之所以写的很细，花了非常多的篇幅，就是因为想要继续往后看，就必须先弄懂结构，否则越往后越不知所云。这部分是我们能继续往后探索的基础。

至于 SlotTable 的结构弄的这么复杂的原因，无疑是为了性能——例如用 IntArray 线性存储、例如用巧妙的位运算等等。至于设计这样的线性数组为什么就能提高性能，我们暂且先不讨论，继续往后看。

小结一下，1.2.1 节我们搞清楚了 SlotTable 中有关 group 和寻址的大部分内容，但是还有一些没有讲清楚的部分，我们梳理一下，大致有这么几条：

- gap 机制相关的问题：
  - groups 或者 slots 数组中，插入、删除、移动等操作对 gap、Anchor、Index 的影响。如何理解？如何实现？
- GroupInfo 一些字段的含义：
  - GroupInfo 中，有 m 和 cm 两个标志位我们之前没有细说，分别是被标记和含有一个标记。这是什么意思？它们有什么区别吗？
  - GroupInfo 中，还有一个叫 node count 的东西。按我们之前的分析，node 仅占一个 slot，那 node count 又是什么？难不成能有多个 node？
- 设计如此复杂的线性结构可以提高性能的原因。

> **另外，再补充一个有关 Group Child 的小问题。**
>
> Group 中提到，Group 实际上是个树状结构，由子 Group 找父 Group 可以通过 Parent Anchor 定位，但是好像没见到有 Child Anchor 这种东西？那么，在子 Group 数量不固定的情况下，如何通过父 Group 定位子 Group 的呢？
>
> **答案是：** 之前我们提过，Group 的子 Group 仅挨着它自己存放，而 Group 的字段中有一项就是 Group 的 size，这个字段也就记录了它的子 Group 的数量，并且，每个 Group 的大小都固定是 5。因此，这个 Group 的子 Group 就全部都可以定位到了。

> **此后，我们约定：**
>
> - 单独提到的 groups 代表整个 IntArray，即所有 groups
> - 单独提到的 slots 代表整个 Array\<Any?>，即所有 slots
> - group 代指单个 group
> - group slots/slots 段代表这个 group 对应的所有 slots，包括可能存在的 node slot、object key slot、aux slot 与 data slot
> - data slot(s)代表这个 group 对应的 Data
> - node slot、object key slot、aux slot 代表这个 group 对应的 Node、ObjectKey、Aux 的 Slot
>
> **并且提醒一下：**
>
> - group 的 DataAnchor 指向的是 group slots 的第 0 个 address，而 data slot 的 address 则要从 DataAnchor 处开始往后偏移可能存在的 node slot、object key slot、aux slot，再往后才是 data slot，不要搞混了。
> - group 的 slot anchor 和 data anchor 不是一回事，slot anchor 指向的是 data slot。

好了，我们带着上面的疑问，继续往下。

#### 1.2.2 代码结构

我们现在清楚了 SlotTable 的结构，还有那么多问题等着我们回答，那下一步该如何继续往下探索呢？不要慌，不要乱，理清思绪，想想现在我们能干什么？

我们可以去看 SlotTable 类的代码了。

> `后文可能以table作为slotTable对象的简称。`

##### 1.2.2.1 属性

先来看看 SlotTable 类中的字段。

```kotlin
internal class SlotTable : CompositionData, Iterable<CompositionGroup> {
    //就是我们之前提到的groups数组，存放了SlotTable中的所有group
    var groups = IntArray(0)

    //groups数组中，group的数量
    var groupsSize = 0

    //就是我们之前提到的slots数组，一个group可以通过dataAnchor定位到这个group对应的group slots
    var slots = Array<Any?>(0) { null }

    //slots中已使用的slot的数量
    var slotsSize = 0

    //active状态的reader的数量，一个SlotTable能有多个reader
    var readers = 0

    //是否存在active的writer，一个SlotTable只能有一个writer
    var writer = false

    //active的anchors
    var anchors: ArrayList<Anchor> = arrayListOf()

    //SlotTable是否为空
    override val isEmpty get() = groupsSize == 0
```

这些字段都是我们认识的，那就直接继续往后走了。

##### 1.2.2.2 方法

**SlotReader/Writer 相关方法**

从之前多次提到 SlotReader 和 SlotWriter，我们能猜到，对 SlotTable 的读写是通过 SlotReader 和 SlotWriter 进行的。由于需要限制“同时只能有一个 SlotWriter 写这个 SlotTable”以及“读与写不能同时发生”这两个条件，设计者把创建 SlotReader/Writer 的代码控制在 SlotTable 内部，让这个 SlotTable 实例来控制谁能读写自己。这样的设计也是比较巧妙了。

这部分代码比较简单，就不解释代码了。总之呢，外部可以通过下面这四个方法来取到 SlotReader/SlotWriter 实例，进而访问 SlotTable。

- slotTable.read((SlotReader)->T)
- slotTable.write((SlotWriter)->T)
- slotTable.openReader()
- slotTable.openWriter()

有关 SlotReader/Writer 的方法，还有 close()方法。在 SlotWriter 调用 table.close()之前，table 是无效的——因为正在写数据时当然不能同时读，当写完数据后，调用 table.close()之后，才能读。

**Anchor 相关方法**

SlotTable 类中有几个关于 Anchor 的方法，在 1.2.2.1 节的字段中，我们看到了 table 有个属性 anchors，它是个 Anchor 类型的 ArrayList，尽管我们现在还不知道这个 anchors 的具体用途，但不妨碍我们先来看看有关它的操作方法。

看一个就够了。

```kotlin
fun anchor(index: Int): Anchor {
    runtimeCheck(!writer) { "use active SlotWriter to create an anchor location instead " }
    require(index in 0 until groupsSize) { "Parameter index is out of range" }
    return anchors.getOrAdd(index, groupsSize) {
        Anchor(index)
    }
}
```

这个方法用于获取指定 index 处的 anchor（没取到则新放一个 anchor）。其中，anchors.getOrAdd(index, groupSize)的作用是，在 anchors 中查找索引为 index 的 anchor，找到则返回，没找到则在该 index 新放置一个 anchor。ArrayList\<Anchor>.getOrAdd 方法中调用了 ArrayList\<Anchor>.search 方法，这个 search 方法就是一个手写的二分查找。

还有几个类似的 Anchor 相关的操作方法，比较简单，就不一一读了。

**Group 和 RecomposeScope 相关方法**

SlotTable 类方法的最后一部分是关于 Group 和 RecomposeScope 的，在完全理解 1.2.1 节后，这一部分的实现逻辑也比较简单（`但是RecomposeScope本身是什么，我们暂不讨论`），就不展开了，这些函数大致有：

- findEffectiveRecomposeScope(group: Int): RecomposeScopeImpl?
  - 从 group 参数代表的 group 的 group slots 开始，寻找第一个有效的 RecomposeScope，如果找不到则不断向父级继续寻找。然后在 invalidate 时，会导致这个 group 重组。
- invalidateGroup(group: Int): Boolean
  - 类似上面的查找，也是去找最近有效的 RecomposeScope，如果 invalidate 会导致重组，则返回 true，否则返回 false 会导致其他形式的强制重组。

**辅助方法**

最后一部分就是一些调试辅助方法了，不作介绍。

---

至此，我们终于把 SlotTable 类读完了，长呼一口气——休息一下，看一眼目前的进度。

<p align=center><img src="https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/c724c6249d6c47c6bf50a0f6550ac41d~tplv-k3u1fbpfcp-watermark.image?" alt="image.png"  /></p>

目前 SlotTable.kt 里就剩下两个最大的类了（`其他的都是一些无关紧要的帮助类`），也就是我们之前提到多次的 SlotReader 和 SlotWriter，一个 400 多行，一个将近 2000 行。我们之前的很多疑问还没得到解答，看来，重头戏就藏在这两个类了。

那么，先看哪个呢？顺序一定不能乱，当然是要先看恐怖的 SlotWriter 了，因为只有知道了数据是如何写入的，才能知道如何去读它——倒不如说，一旦弄明白了如何写入，那该如何去读自然而然也就都清楚了。

因此接下来我们带着之前的所有疑惑，直奔 SlotWriter。

## 2 SlotWriter

尽管这个文件有接近 2000 行，但是不要怕，思路必须清晰。接下来我们理理思绪，解读整个 SlotWriter 分为三步：

1.  大致看看它有哪些属性，这是为第 2 步打下基础。
2.  直奔它的各种基本操作方法（`即group和slot的增删改查移动等`）。这一部分是为第 3 步打下基础，非常重要，不过，重点别搞错了，读操作方法的源码只是为了让我们更熟悉整个 SlotWriter 的基本运作，而并非真正要去看完每一行代码。其实，以我们在第 1 节中打下的基础，啃完所有操作方法的源码并不会有太大问题，但是真没必要。因此这一部分，我们也只挑几个典型的操作方法去看看，至于剩下的，有需要用到时我会给出这个方法的简要相关说明。
3.  尝试接触 SlotWriter 的核心，也就是外界 Compose 框架通过操作 SlotWriter 来给 SlotTable 写数据的代码（`startGroup等`），那才是我们的重点。

把这些全部都搞定，那么我们的 SlotWriter 这一节也就结束了。下面直接开始，我们先看看 SlotWriter 里面常见的一些属性和概念。

### 2.1 属性和概念

**val table: SlotTable**

这个就没什么好说的了，SlotWriter 在构造时就会把要写入的 table 传入。

**groups: IntArray = table.groups**

groups，我们的老朋友了，但是 SlotWriter 里的 groups 的注释告诉了我们更多的信息：

- 当有新 group 插入、且会导致 groups 需要扩容时，这个 groups 可能发生变化。
- 由于 gap 的存在，groups 内有些空间是代表 gap 的，那么有效的、代表 group 的区域如何分布呢？见下图。（这就是 1.2.1.2 节的内容）

![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/3fcc67ba0ce0490aa69e5467278ef40c~tplv-k3u1fbpfcp-watermark.image?)

**slots: Array\<Any?>**

slots，与 groups 几乎完全一样，包括 gap 的分布方式，因此不再赘述，看上图即可。

**anchors: ArrayList\<Anchor>**

一个 anchor 数组，用来记录一些 group index，但具体的意义我们暂不知晓。（`其实是外界操作SlotWriter时留下的一些定位标记，这篇文章中我们不会过多介绍anchors具体的用途，我们更多关注SlotTable和SlotWriter本身。`）

> `后文中，为了区分这个anchors和其它anchors，会把这个anchors称为SlotWriter的成员变量anchors或类属性anchors，请注意区分。`

**groupGapStart: Int**

gap 开始的索引。对照 1.2.1.2 节的图的 Address 序列进行理解。

**groupGapLen: Int**

groups 中 gap 的数量。对照 1.2.1.2 节的图的 Address 序列进行理解。

**slotsGapStart/slotsGapLen: Int**

类比上面 groups 的，同样理解。

**slotsGapOwner: Int**

这个 gapOwner 的概念会难理解一些。首先，slotsGap 的 owner 是指一个 group，而不是一个 slot。而 gapOwner 是把 gap 本身也当作了一个 group 来看，因此 gapOwner 的实际值要在 gap 前的最后一个 group 上+1，换句话说，owner 的取值范围是\[1,size]，而非\[0,size-1]。

![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/f2bd8e4016e64e98babd8648ef91b2ab~tplv-k3u1fbpfcp-watermark.image?)

例如，在这张示意图中，slots 中的 gap 跟在 group1 的 slots 之后，gap 属于 group1 后的一个“group”，那么 owner 就是 group1 的 index 再+1，在这张图中，也就指向 group2 了。（`简而言之就是owner要多+1`）

**val startStack/endStack: IntStack**

显示启动的 group 会被记录在 startStack 里，这个 stack 是一个 IntStack 类型，就是一个全为整数的栈结构，后进先出。endStack 与之对应。

这俩玩意都是与 startGroup、endGroup 等重要操作有关的，我们先不急着理解。

**var currentGroup: Int**

就是当前即将要进行 start/skip 等操作的 group。

**val isGroupEnd get() = currentGroup == currentGroupEnd**

如果说 currentGroup 已经在 group 的末尾了（`Group是树状结构，可以包含子Group的`），意味着访问完了，要去调用 endGroup。

暂且先介绍这些概念。

### 2.2 基本操作方法

一些太细碎辅助性的方法（`例如，根据parentAnchor获取parent group、根据index获取groupKey之类的`）我们就不过多介绍了，只要第 1 节看懂了，这些都没问题。

这一节我们主要看看对 groups 和 slots 的基本操作方法，例如增删、移动、扩容等等。

#### 2.2.1 slot 的操作方法

首先看看 slot 的。

##### 2.2.1.1 moveSlotGapTo

`moveSlotGapTo(index: Int, group: Int)`

我们看的第一个方法，moveSlotGapTo，作用是 slots 中的 gap 移动到 group 对应的 slots 段中的 index 这个位置，以便于能够为 group 添加一些新的 slots。

> `这里提一嘴，参数中的index和group应该是有关联的，只是函数中没有再去校验（反正是内部源码，也不是暴露给一般开发者调用的，多校验一下就多一点开销）。这里说的有关联的意思是，参数index应该是在group对应的slots段之内的，后面的几个函数也都是这样。`

这整个函数的实现主要分了两步，我画了两张示意图。

**第一步**

![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/b10c54df18c64938b694b37d889f16e2~tplv-k3u1fbpfcp-watermark.image?)

第一步，把 slots 中的 gap 从 gapStart 位置移动到 index 位置。

图中的蓝色圆圈代表的是需要移动的数据，可以看到，当 gap 移动时，实际上受影响的只有蓝色圆圈的数据，而再往前或者往后，是不会有任何影响的。说白了，这个操作就是把蓝色圈圈的数据和 gap 做交换。

图中的横线表示 slots，横线上表示 before，也就是移动前，横线下则是 after，也就是移动后。

我们看情形 1，当 index\<gapStart 时，直接用数组的 copy 方法移动蓝色部分的数据：目标数组 destination 还是自己，而 destinationOffset 则是 index+gapLen，要 copy 的蓝色部分的起始位置 startIndex 是 index，结束位置是 gapStart。copy 后，蓝色圈就到后面去了，然后我们再把 index\~index+gapLen 段置 null，即形成了新的 gap，完成了换位。

情形 2 以及当 gap 不重合时的情形，就不赘述了，可以自己看看。

**第二步**

![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2e60bb1cbd48423d802b35fb58b42fa7~tplv-k3u1fbpfcp-watermark.image?)

那么 moveSlotGapTo 方法的第二步，则是更新 dataAnchor。由于我们移动了 gap，而前面已经说过，Anchor 是与 gap 位置有关的，因此要更新受影响的 anchors。

图中代码只贴了 newSlotsGapOwner>slotsGapOwner 的情况，另一种情况类似，就不贴代码了。

可以看到，受 gap 移动影响的 anchor 是 group2 和 group3 的 data anchor，因此我们要更新它们。具体的代码逻辑，对照图慢慢看就能看懂，不再赘述了。

整个 moveSlotGapTo 就分析到这里。只有当有了 gap 我们才能执行插入等操作，我们接下来就看看插入。

##### 2.2.1.2 insertSlots

`insertSlots(size: Int, group: Int)`

我直接根据源码给出我归纳的步骤。

1.  调用 moveSlotGapTo(currentSlot, group)，先把 slots 中的 gap 挪到要插入 slot 的 group 所属的 slots 段。这里 currentSlot 也是 group 对应的 slots 段的索引。

2.  如果当前 gapLen 不足以插入 size 个 slot，则扩容。

    1.  新的 gap 的大小为 slots.size \* 2、slots.size - gapLen + size、32 三者的最大值。依据这个大小创建一个 newData 数组。
    2.  然后依旧是调用 array.copyInto，通过两次 copyInto 调用，把数据从旧数组拷贝到新数组，这样 gap 自然就扩大了。

    ![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7d0ac0f5e6164556bb9ebea048ebe82f~tplv-k3u1fbpfcp-watermark.image?)

3.  更新 slots、slotsGapLen、slotsGapStart、currentSlotEnd 等各个受影响的 SlotTable 的属性。

可以看到，insertSlots 的实现还是比较简单和清晰的。先把 slots 的 gap 移动到要插入 slots 的 group 对应的 slots 段下，然后如果 gap 长度够，则直接 insertSlots，否则扩容。

##### 2.2.1.3 removeSlots

`removeSlots(start: Int, len: Int, group: Int)`

对于 group 对应的的 slots 段，从 start 开始移除 len 个 slots，主要步骤如下。

1.  调用 moveSlotGapTo(start + len, group)，把 gap 移动到要删除的部分的末端。
2.  直接把 start\~start+len 段置 null，并修改 slotsGapStart 和 slotGapLen，就把 gap 扩容了，也就相当于 remove 了 slots。

这样，removeSlots 保证了 slots 被删除（实则为置 null 了），然后把 remove 掉的部分一并算入 gap 段内，同时，这样也保证了 slots 中只有唯一一段连续的 gap，以便后续进行其它操作。

#### 2.2.2 group 的操作方法

slot 的操作方法暂时先看上面那些，接下来看看 group 的，大多与 slot 的操作方法类似。

##### 2.2.2.1 moveGroupGapTo

`moveGroupGapTo(index: Int)`

groups 数组与 slots 数组一样，也有一段 gap，自然也有 moveGap 方法，那么这里的参数 index 就是要移动到的索引，可以对照 1.2.1.2 寻址一节的图来理解。主要步骤如下。

1.  更新 anchors。这里的 anchors 是指 2.1 节提到的成员变量 anchors，gap 的移动导致所有记录的 anchors 需要更新。更新的逻辑对我们来说已经很清楚了，因为我们已经知道了 anchor 的计算规则，那么对比新旧 gap 找出受影响的那些 anchors，然后按照 anchor 计算规则重新计算值就行。
2.  调用数组的 copyInto 移动数据。这一步与 moveSlotGapTo 类似，可以参照 moveSlotGapTo 的图理解。需要注意的是，groups 中我们要按照 Address 序列来操作，也就是把 Address 序列转换为真实物理地址，说白了也就是\*5，这一点也在寻址一节中讲过了，而 moveSlotGapTo 不需要，因为每个 group 的 slots 段长度不固定，本身就是根据 group 划分和定位的。如果前面理解透了，这里的地址转换是非常好理解的。
3.  gap 移动可能导致 parent anchors 也需要更新，因此，最后更新受影响的 parent anchors。
4.  最后更新标记，groupGapStart 更新为 index。

<span id="2-2-2-2"></span>

##### 2.2.2.2 insertGroups

`insertGroups(size: Int) `

插入 size 个 group 到 currentGroup 之前，这些插入的 group 和 currentGroup 都有同一个 parent。主要步骤如下。

1.  调用 moveGroupGapTo(currentGroup)。可以发现，不论是 insertGroups 还是 insertSlots，都是通过先把 gap 移到想要 insert 的位置，再直接往 gap 里插入，来实现的。
2.  如果 gapLen 不够，则扩容。这里与 insertSlots 也几乎一样，不再赘述。
3.  更改 currentGroupEnd、groupGapStart、groupGapLen、slotsGapOwner 等成员变量，以表明已经插入了若干新 group。
4.  设置 data anchors。对于新插入的 group，它们的 data anchor 和 currentGroup 一样。

##### 2.2.2.3 removeGroups

`removeGroups(start: Int, len: Int): Boolean `

group 的 remove 操作与 slot 的也是类似的。这里先把 gap 直接移动到 start 位置，然后把 start\~start+len 段都标记为 gap，就完成了所谓的 remove 操作，同时也保证了 gap 只有一段且连续。

除此以外，group 的 remove 操作还需要删除可能的 anchors，这个 anchors 是指成员变量 anchors，removeGroups 函数的返回值也是表示 anchors 是否都被移除了。

另外，slotsGapOwner 和 currentGroupEnd 等标记成员变量也要进行相应更新。

最后，如果 parent 含有 groupMark 的话，还要更新 parent 的 mark，但是我们目前还没介绍 mark 是啥，所以先不管它。

乍一看 remove 的我们分析到这里也就结束了，但是，这里有一个问题。我们把若干 group 移除了，但是好像没同步移除它们对应的 slots 呀——其实是有的，别急，只不过移除 slots 的代码在其他地方，不在这儿。

### 2.3 核心方法

到这里，我们对 group 和 slot 的基本操作方法都有了一定的了解。接下来就是重头戏了。我们要来看看，Compose 框架怎么操作 SlotWriter 来改变 SlotTable 的。

> `在继续之前，我再次提醒一下，由于代码太多太庞大，我们的思路和当前任务一定要清晰，我们要清楚这一次我们探索的边界何在，千万不能乱，否则很容易迷失。`
>
> `整个第2节（即SlotWriter一节），我们涉及到外界（即Composer、Recomposer等角色）的地方仅仅只有它们操作SlotWriter的地方。换言之，我们目前的重心还是放在SlotWriter内部的。我们目前的任务是看SlotWriter的startGroup、endGroup、moveGroup等操作，毕竟我们整篇文章是侧重SlotTable本身，而不是侧重Composer等外界的组合重组逻辑的。因此，如果你要问例如Composer中的start、end是如何协作的，它们又和我们写的@Composable有什么关系、亦或者Recomposer相关的问题，那么，这篇文章里是不会过多提及的，仅仅只会大致介绍一点相关的部分，相当于提前铺垫一下，浅尝辄止。`
>
> `Jetpack Compose的整套体系过于庞大，从Compose编译器，到Composer、Composition、Recomposer、SlotTable，再到与Android接轨的AndroidComposeView等等，肯定不是一口气能啃下来的。所以我在文章中间的这里再次提醒，我们要清楚这次探索的边界，必须时刻清楚这部分我们是在了解什么内容，哪些内容可以目前仅做大致了解等等。`
>
> `好了，我们继续。`

#### 2.3.1 从 Composer 看起

我们之前已经知道，Compose 编译器会把我们写的@Composable 函数包装成各种各样的 group。从 Composer.kt 中，我们可以知道有很多种类型的 startGroup，例如：

- `startReplaceableGroup/可替换组` - 指不能在同级组之间移动，但可以被移除或插入的组。编译器会在@Composable 函数中的条件逻辑分支（例如 if 表达式、when 表达式、提前 return 和 null 合并运算符等）周围插入这些组。

- `startMoveableGroup/可移动组` - 指除了被移除或插入之外，还可以在其同级之间移动或重新排序并保持 SlotTable 状态的组。可移动组比较昂贵，仅会由编译器在 key 函数调用后插入（即用于长列表等）。

- `startRestartGroup/重启组` - 用于记录一个@Composable 函数的组，这个被记录的函数可按需被部分再次调用。

- `startNode` - 在这里，我们终于要解释之前提到过多次的 Node 了。在 Composer.kt 文件的末尾，有一个类叫 GroupKind，即 Group 的种类，从这个类我们能知道，Group 一共有三种类型：

  - Group - 普通 Group
  - Node - Node Group
  - ReusableNode - 可重用的 Node Group

  Node 类型的组实际上跟踪记录了一段代码，这段代码用于创建或更新 Node 节点。而这里的 Node 节点指的是，由 Composition 所象征的树的节点。我们知道，Composition 虽然象征着一个树结构，但并不是 Compose 用于实际渲染的树，这里的 Node 节点指的就是，生成的实际渲染的树的 Node 节点。而我们的 Node Group，存的是一段可执行代码。这段代码是用于创建或更新 Node 节点的。

  我们之前看到的 Group 的字段中，有关 Node 的部分，在这里就得到解释了。如果 start 了一个 Node Group，则 Group 的字段中，会有相应的记录。

  > `到这里了，我们必须意识到一件事，或者说有一种观念，即，函数也是一个对象。`
  >
  > `Kotlin和Compose的设计中，大篇幅能看到这样的思想。函数就是一段可执行的代码，再说白点，就是一系列操作。lambda的概念与之非常类似。而函数的定义和实现就是定义了这些操作，而函数的调用才是去执行这些操作。因此我们当然可以把这些操作先只是定义出来，而不去执行它们，而是以对象的形式，把它们存下来。`
  >
  > `这就是Group中经常能看到的，Group的Data实际上是一个Lambda，或者说一个函数对象。同样，在Composer的代码中，也有大量类似的概念，例如Change等。它们都是先定义好操作，然后把它当对象存起来，需要时再去执行。`

除了上面提到的这些以外，当然还有一些 startXXXGroup 的方法，例如 startDefaults、startRoot、startReusableNode、startReusableGroup 等（还有一些别的方法，例如 buildContext 方法，也会调用 startGroup），具体的我们不再去细究了，再往下看就有点离题了。我们这篇文章只是研究 SlotTable，不研究 Composer。

回到正题，我们继续往下跟。我们刚刚看到 Composer 中各种 startXXGroup，不论是谁，它们最终都调用了一个方法——start，那么，这个 start 就是重中之重了，我们来看一看。

    start(key: Int, objectKey: Any?, kind: GroupKind, data: Any?)

不同的 startXXGroup 方法，对 start 传入了不同的参数，这些参数我们都不陌生了——key、objectKey、kind、data。

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2a4b6dad4f9c4cb09cf9c199d376cbaf~tplv-k3u1fbpfcp-watermark.image?)

可以看到，只有 startNode 和 startReusableNode 传入的 GroupKind 不是 GroupKind.Group。总之，我们的各类数据在这里就要被打包成 Group 存进 SlotTable 了。我们具体来看看这个 start 函数。

这个 start 函数很长，但整体脉络很清晰。我们暂时只关注它与 SlotWriter 接轨的部分——当 Composer 处于 inserting 状态时，start 函数中会直接去操作 SlotWriter 来直接新增 Group。

> `若Composition当前正要插入节点到树中，则处于inserting。第一次组合时，一直处于inserting，重组时，当有新节点要被插入到树中时，处于inserting。`

在插入 Group 的前后，会成对调用 writer 的 beginInsert 与 endInsert 方法。

> `（以下简称begin）由于Group是树状结构，因此begin是可以多次调用的。当begin被调用，而end尚未被调用时，如果这时再去调用begin，就意味着发生了嵌套，即，再次begin的是当前Group的子Group。`
>
> `那么，在当前（也就是最外层）的Group要begin时，我们记录一下currentGroupEnd，然后，当前（最外层）的Group要end时，通过对比之前begin时记录的currentGroupEnd，我们就能知道这期间它的子Group是否有插入或删除操作——因为我们之前说过，子Group是紧接着当前Group排列的，currentGroupEnd的范围是包括了所有子Group的。`

继续 start 的流程，刚刚提到如果是 inserting，就操作 writer 进行新增 Group，调用的代码就是下面三行。

```kotlin
when {
    isNode -> writer.startNode(key, Composer.Empty)
    data != null -> writer.startData(key, objectKey ?: Composer.Empty, data)
    else -> writer.startGroup(key, objectKey ?: Composer.Empty)
}
```

那么，不论 startNode/Data/Group，它们都相当于一堆重载方法，最终都调用到 SlotWriter 的 startGroup 方法。接下来，我们就进入这个方法继续跟进。

#### 2.3.2 start/endGroup

`startGroup(key: Int, objectKey: Any?, isNode: Boolean, aux: Any?)`

startGroup 中，根据是否处于 inserting 分为两种情况。

如果要新增节点时，就会一直处于 inserting 模式，我们这一节先来看从零开始新增节点的过程。

1.  首先调用之前[2.2.2.2 节](#2-2-2-2)讲的 insertGroups(size=1)，把 gap 移动到 currentGroup 前，然后直接往 gap 中新增一个 group，并为它设置好 group 的基本信息。（`例如key、objectKey、node、aux、parentAnchor、dataAnchor等。然后，如果有node、aux、objectKey等辅助字段，还要按1.2.1.4节图中介绍的那样，设置好相应的dataSlots信息。`）
2.  由于是新增一个 Group，那么，新增的 Group 一定是树上的叶子节点，即没有子 Group。在新增之后，我们会暂时把 parent 设置为新增的这个 Group 自己。（`此时，parent变量的含义是：如果要新增新的节点，新的节点的父节点就是parent。`）
3.  最后，把 currentGroup 设置为 parent+1。（`此时，currentGroup变量的含义是，如果要新增新的节点，新节点的位置挂在parent下。`）
4.  更新其它变量。（`例如currentSlotEnd、currentGroupEnd等。`）

单单这么看，其实会感觉构建过程云里雾里的。没错，别忘了 endGroup。我们之前提过，endGroup 和 startGroup 是成对的。有一次 startGroup 调用就要有一次 endGroup 调用。那么我们接下来就一并看看 endGroup。

当处于 inserting 时，endGroup 只干了这么两件事：

1.  更新 groupSize、nodeCount 等值——哪个 group 的？当然是指更新与这个 endGroup 对应的 startGroup 中新增的那个 group 的 groupSize 和 nodeCount。（`从这里也能看出来，end和start是一对，当调用完end，才算真正完成一个Group的新增。`）
2.  currentGroup 保持不变，而 parent 变量更新为 parent 的父 Group。

好了，整个从零开始构建树的算法就是这样。如果你还没反应过来，那么我画了一张示意图，请看。

![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/1af09dcf59044945bf073f64cbadb5f9~tplv-k3u1fbpfcp-watermark.image?)

图中，黄色代表 currentGroup，蓝色代表 parent，而 currentGroup 在 inserting 时代表即将新增的节点，因此以虚线表示。在底下，我画出了每次操作后 groups 的 Index 序列存储的情况。可以看出，这样方式的构建，也恰好符合我们在[1.2.1.4 节](1-2-1-4-anchor1)中提到的，子 Group 在存储上就排在它自己后面。

可以大概猜出，Composer 会去按想要构造的结构，在 inserting 状态下，以特定的顺序去调用 startGroup 和 endGroup 方法，可以说，start/endGroup 的调用顺序和次数决定了一棵树最初的样子。比如，inserting 时连续调用 startGroup 将导致树将按深度优先的方式生长。

另外提一嘴，startGroup 是 Composer 唯一能插入新 Group 的地方——如果当前是 inserting 状态，则会新 Group。（`还有一个叫bashGroup的操作也能在SlotTable中插入一个Group，而且是直接插入parent，但那是Recomposer触发的行为，且用途意义也和单纯的新增构建不一样，我们暂且不讨论。`）

需要注意的是，inserting 新增时，好像并没有一并传入新增的 Group 的 data slots，顶多只只填充了一些新增的 Group 的辅助信息 slot，那么 data slots 在什么时候设置是一个问题，不过我们之后再说。此外，非 inserting 时调用 startGroup 的逻辑，我们也往后稍稍。

#### 2.3.3 moveGroup

看完了新增，我们再看看移动 Group。

在 Composer 的 start 函数里，除了可能新增节点外，还有一种可能，就是在兄弟节点之间进行移动。这里的移动仅指，从后往前移。至于 Composer 什么时候会进行这样的操作，以及为什么只能从后往前移，我们这篇文章不会提及。我们的侧重点在 SlotWriter 移动 Group 的具体操作。

`moveGroup(offset: Int)`

moveGroup 方法会将 currentGroup 后的第 offset 个 group 移动到 currentGroup 前，这里的 offset 个是指与 currentGroup 同级节点的往后 offset 个，而非 groups 数组的往后 offset 个。

我们首先定位到目标的 Group 节点 groupToMove，然后计算出移动的长度 moveLen=groupToMove.groupSize，再计算出需要一并移动的 groupToMove 的 slots 的索引 dataStart 和 dataEnd。

移动时，不能处于 inserting 模式。移动的整体流程是：

- 在新位置插入空位
- 把待移动的 group 和对应 slots 拷贝到空位
- 删除原位置的原数据

但是，具体流程并没有这么简单，因为我们还要考虑间隙的移动导致的各类 anchors 必须正确更新值。那么，具体的操作顺序就非常重要了，步骤如下。

1.  对于 slots，在目标新位置插入空位（必须是第一步）。

    `insertSlots(moveDataLen, max(currentGroup - 1, 0))`

2.  对于 groups，在目标新位置插入空位。

    `insertGroups(moveLen)`

3.  把要移动的 groups 拷贝到新位置。

    ```kotlin
    groups.copyInto(
        destination = groups,
        destinationOffset = currentAddress * Group_Fields_Size,
        startIndex = moveLocationOffset,
        endIndex = moveLocationOffset + moveLen * Group_Fields_Size
    )
    ```

4.  把要移动的 slots 拷贝到新位置。

    ```kotlin
        slots.copyInto(
            destination = slots,
            destinationOffset = currentSlot,
            startIndex = dataIndexToDataAddress(dataStart + moveDataLen),
            endIndex = dataIndexToDataAddress(dataEnd + moveDataLen)
        )
    ```

5.  更新受影响 group 的 dataAnchor。

    ```kotlin
        for (group in current until current + moveLen) {
            groups.updateDataIndex(groupAddress, newAnchor)
        }
    ```

6.  更新受影响的成员变量 anchors 中的 anchor。

    `moveAnchors(groupToMove + moveLen, current, moveLen)`

7.  删掉之前的旧 groups。

    `removeGroups(groupToMove + moveLen, moveLen)`

8.  更新受影响 group 的 parentAnchor。

    `fixParentAnchorsFor(parent, currentGroupEnd, current)`

9.  删掉之前的旧 slots（必须是最后一步）。

    `removeSlots(dataStart + moveDataLen, moveDataLen, groupToMove + moveLen - 1)`

以上就是全部的步骤和顺序了，有些步骤之间的顺序不能乱，比如 7 必须在 9 前面，因为删 slots 时需要移动 gap，这个操作依赖于相应 group，必须先保证相应 group 不再是旧值才行。总之，按以上顺序，我们能正确完成把目标 group 从后往前移的操作。

上面每个步骤的操作，大部分都是我们分析过的函数，还有像 moveAnchors、fixAnchorsFor 这样的函数我们没有分析，它们实际上比较简单，自己直接看源码是没有太大困难的，因此就不再占篇幅去讲了。

另外，外界 Composer 除了新增和移动 group，还可以 removeGroup，不过 removeGroup 的实现也比较简单，因此也不再去精读了。

此外，与移动有关的还有几个函数，moveTo、moveFrom，moveIntoGroupFrom 以及它们依赖的 SlotWriter.moveGroup 函数。这几个函数，细节我们就不看了，大体实现逻辑也是先开辟空位，然后拷贝，然后删除旧数据，并且更新该更新的 anchors 和变量等等。这些函数是用于在两个 SlotWriter 之间移动 group 的，如有必要，我们在之后的文章的相应部分再去分析它们。

### 2.4 目前可以获得的情报

到此为止，这个 2000 行的 SlotWriter，我们基本上就看完了，长舒一口气\~

我们小结一下，基于第 1 节我们对 group、slot、slotTable 结构的了解，在本节中，我们先大致分析了 SlotWriter 类的属性和基本操作方法，然后详细说明了 SlotWriter 的基本操作方法，然后从 Composer 切入，详细分析了 SlotWriter 的核心操作方法。

我们已经揭开了整个庞大的 Compose 框架的冰山一角了，可喜可贺。

那么同样地，我们必须理清思绪，看看目前还有哪些我们遗留的，说要以后再看的问题。

- anchors 相关
  - 我们已经知道成员变量 anchors 是 Composer 用来打标记的，那么它到底起了一个什么作用？
- node 相关
  - 我们在分析的过程中，实际上是忽略了 node 相关的内容的。虽然在 2.3.1 节中稍微解释了一下 node，但我们仍然对它的具体概念模糊不清，例如：
    - nodeCount 是有什么含义？具体有什么用？如何计算？
    - nodeCountStack 的用途？
- mark 相关
  - 还记不记得我们在第 1 节提到了一个 mark，这个 mark 也被我们全程忽略了。那么它又是干什么的呢？
- 操作方法相关
  - 2.3.2 节中讨论 start/endGroup 时，只看了 inserting 下的情况，那么非 inserting 时呢？
  - 还有一些操作，例如 bashGroup、seek、skipGroup，它们有何作用？
  - moveFrom/To 等这类涉及两个 SlotWriter 的操作，有何作用？
  - startGroup 新增 group 时，并没有设置 data slots，那 data slots 是什么时候设置的？
- Composer 相关
  - 2.3.2 节提到，Group 树的结构是由 Composer 调用 start/end 的次数和顺序决定的，那么 Composer 如何决定这些的？start/end 的调用是如何组织的？
  - 除了新增以外，删除、移动等等操作又发生在何时？
  - 什么操作会涉及两个 SlotWriter？
  - 除了 SlotWriter 外，别忘了还有个 SlotReader。它们是如何协同运作的？
  - ...

我们看懂了 SlotWriter 已经是很不容易了，但随之而来的是一个更庞大、更复杂的角色等着我们——Composer。要想真正搞懂所有疑问，就不得不深入 Composer 去探索了——这个 Composer.kt 有 4000 多行。

好了，别担心。Composer 的探索我们不会在这篇文章进行，那是下一篇的内容。这一篇文章多次提到 Composer，只是为了搞点铺垫，让我们先跟它打个照面。我们的重心还是在 SlotTable 和 SlotWriter/Reader 内部的。

到这里，SlotWriter 的我们目前能分析的所有内容就差不多要分析完了。最后，作为收尾，我们来看看 SlotWriter 的 close 方法。

#### 2.4.1 close

对于 close()，我想说的是，如果你查找一下 Composer 中 close 函数的调用处，就会发现一个非常有意思的事情。例如，在 Composer 的成员变量定义处，或者创建一个新的用于写操作的 SlotTable 之处，它的代码是这样的。

```kotlin
//成员变量定义
private var writer: SlotWriter = insertTable.openWriter().also { it.close() }

//创建新的用于插入的slotTable
private fun createFreshInsertTable() {
    runtimeCheck(writer.closed)
    insertTable = SlotTable()
    writer = insertTable.openWriter().also { it.close() }
}
```

啊？没搞错吧，一创建就把它关了。

那么实际上，close 函数中做了两件事。

- 把 gap 移动到 SlotTable 的最后。
- 把 writer 中对 groups、slots、groupsSize、slotsSize、anchors 等属性的更新保存到 slotTable 中。（`比如当writer发生扩容时，groups、slots会变化，且其他操作时size、anchors都可能变化`）

哦，那么上面的操作就可以理解了。刚对这个 slotTable 创建一个 writer 就 close 的目的实际上是想做第一件事，就是把 gap 移动到最后，这样 groups 的 Index 和 Address 序列就没有区别了，方便后续操作。

负责收尾的 close 就分析到这里。

## 3 SlotReader

现在可以说，我们已经把最难的 SlotWriter 部分啃完了，在这个过程中，我们对 SlotTable 存储数据的方式又有了更深的理解，并且，在 Reader 中，gap 总是在最后，groups 的 Index 和 Address 序列相同，也就没有 Index 和 Address 的概念之分了，这样，SlotReader 读起来会轻松很多。那么，这一节，就来看看 SlotReader 吧。

什么？你说 SlotReader 已经看完了？怎么可能！

别怕，我告诉你，这是真的，SlotReader 确实已经看完了，在第 1 节和第 2 节里，我们几乎已经分析清楚了所有内容。这下你再去看一眼 SlotReader 的代码，就是赤裸裸地一览无余，几乎所有内容理解起来都非常简单。

就比如我们在 SlotWriter 中有各种各样的标记，但是 Reader 中就只有寥寥几个，比如 currentGroup/Slot、currentEnd、parent 等等，它们就相当于一些游标，记录一下当前读到哪个 Group 了而已。还是那句话，我们已经在写的时候弄清楚了所有结构，读还不会读吗？

因此，接下来，我只再补充几个关于 SlotReader 的小点，我们就算读完 SlotReader 了。

**1、解释一下 SlotReader 中前几个成员变量的注释。**

```kotlin
//A copy of the SlotTable.groups array to avoid having to indirect through table.
private val groups: IntArray = table.groups
```

比如这个 groups 的注释，他说创建了一个 SlotTable.groups 数组的副本，避免不得不间接通过 table 来访问。

我一开始还以为他想创建这个 groups 数组的副本，但似乎 reader 本身也只是负责读啊，创建副本干啥？后来才反应过来。

他的意思是说，他定义了一个叫 groups 的变量，直接给它赋值为 tables.group，这样以后想在 SlotReader 内访问 groups，就不用每次多写一个“table.”。他说的 copy 是这个意思。

**2. 简单解释一下几个成员变量。**

- `currentGroup` - 一个游标，表示 startGroup 或者 skipGroup 中，即将要被操作的组。
- `parent` - currentGroup 的父 group，它是 startGroup 启动的上一个 group。
- `currentEnd` - parent 的末端。
- `emptyCount` - 记录 beginEmpty 调用的次数。
- `currentSlot` - 一个游标，代表 parent 的当前 slot，只要还没有移动到 currentSlotEnd，调用 next 方法时，它就会移动到下一个 slot 的位置。
- `currentSlotEnd` - parent 的 slots 的最末端。

注意，这里的 currentSlot 和 currentSlotEnd 与 SlotWriter 中的有所不同，Writer 中记录的索引游标是整个 slots 数组的游标，而 Reader 这里由于只是读，currentSlot 只要记录当前 parent 的游标即可。换句话说，有效的 currentSlotEnd 取值范围是 0 到`if (current >= groupsSize - 1) slotsSize else groups.dataAnchor(current + 1)`。而有效的 currentSlot 取值范围是\[0, currentSlotEnd)。甚至，在 Reader 的 startGroup 中，currentSlot 的值会直接定到 slotAnchor，而非 dataAnchor，这也是为了方便读取。

要解释的就是这些了。其它的以我们对 SlotTable 的了解，都能轻松看懂。

**3、emptyCount/beginEmpty/endEmpty**

这个 emptyCount 就是一个记录变量。每当处于 inserting 模式下，Composer 调用 start 时，这个变量就会+1，对应地，调用 end 时-1。它也类似之前 SlotWriter 中提到的，是可以嵌套调用的。

这个变量用于做记录，以保证 next 和 skip 等操作的正确性。例如，在 next 方法中我们会读取下一个 slot，但是，当 inserting 时，自然是不能读取的，因此，当 inserting 时，emptyCount 大于 0，读到的永远就是 Empty。类似地，在 skip 中也有类似的控制，skip 时不能处于 inserting。除此外，在其它的一些函数中也有类似控制，例如 Reader 的 startGroup 若处于 inserting 则不会进行。

```kotlin
fun next(): Any? {
    if (emptyCount > 0 || currentSlot >= currentSlotEnd) return Composer.Empty
    return slots[currentSlot++]
}
```

那么，在 SlotReader 一节的最后，我们扫个尾，简单看看 SlotReader 中的 startGroup、endGroup、reposition 等读取 SlotTable 的函数。

### 3.1 访问 SlotTable

Reader 去读 SlotTable 的方式也是类似 Writer 的，通过更改 currentGroup、parent 等游标的定位来访问，但在细节上又与 Writer 有所不同。

在 Reader 中，可以通过 start/endGroup、skip、reposition 等操作控制游标。就如刚刚所说，这几个函数的有效调用都需要非 inserting。它们的实现代码不难，比 Writer 的短多了，同样地，我也给了一张示意图。

![image.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2eb1c8256e104378834be9fad36f7191~tplv-k3u1fbpfcp-watermark.image?)

这个图简单展示了各个函数的作用以及访问的流程。我们可以通过这些函数来对游标进行控制，进而访问需要访问的 Group。

> `实际上的流程可能并没有那么简单，你可能会发现，如果在初始状态，我直接调用startGroup，然后调用reposition(5)，然后endGroup，如果只依照我们目前的理解，这个操作并不会报错，但是，这时候就会得到一个不匹配的currentGroup和parent，这是有问题的。因此，这一现象我们暂且当作疑惑记下，因为仅从目前我们掌握的信息来看，并没法解释这一点。我猜测实际上是会报错的，可能和emptyCount的runtimeCheck有关，但这要等我们以后探索了Composer才能知道了。`
>
> `所以这个图就是简单解释了一下各个函数，但是至于实际上它们是怎么协作的，暂时无从知晓。`

<span id="4"/>

## 4 小结

好了，这篇文章到这里就终于结束了。恭喜你，SlotTable.kt，这 3000 多行代码，绝大部分内容已经完全弄清楚了。

剩下的，除了我们之前提到的暂时不讨论的一些疑问，就只有边角料了——一些无关紧要的辅助函数、以及一些自己能够轻松看懂的辅助代码。

<span id="4-anchor1"/>

现在我们再来总结概括一下 SlotTable，首先，就如文章开头所说，SlotTable 就是 Compose 框架储存各类数据的地方。另外，SlotTable.kt 文件里，还有两个大类 SlotWriter 和 Reader，它们提供了对 SlotTable 的构造和访问能力。

由于我并没有找到其它关于 SlotTable 的详细分析，因此，这篇文章，是我硬读这几千行代码，然后思考出来的（文中的那些各种各样的流程图，也是我自己画的，不是网图，如果看不清，这里有[高清大图](https://github.com/FantasticPornTaiQiang/AndroidNote/blob/main/compose/SlotTable/SlotTable.png)）——我想说的是，可能会有错误或者描述不清的地方，欢迎纠正和讨论。

至于为什么会去读这个源码呢？纯粹是好奇和兴趣。它确实太底层了，以至于读完也对 Compose 的使用没啥帮助，但是，如果想继续探索 Compose 的原理，SlotTable 就是必须要搞清楚的一个东西。

那么，接下来该继续往哪里探索呢？我们目前在文章中留下的绝大部分疑惑（包括第 0 节提出的好几个疑问），现在看来都与 Composer.kt 有关。所以继续探索 Compose 框架的下一站，就确定为 Composer.kt 了。这 4500 行代码，将会进一步揭露 Composer 的神秘面纱。

作为 Compose 探索的第一篇文章，就写到这里吧，下一站，Composer，出发。
