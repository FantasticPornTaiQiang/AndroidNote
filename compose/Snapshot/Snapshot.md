## \[undefinedChapter] 数据结构

Snapshot系统里有两种自己实现的数据结构，它们是针对Snapshot系统中的某些特定的使用场景进行的优化。我们先来看看这两个数据结构。

### \[undefinedChapter].1 SnapshotIdSet

SnapshotIdSet是Snapshot系统中用于记录invalid的Snapshot的id的集合，它针对invalid的id的记录做了优化。

如果你对这个数据结构本身不感兴趣，那么这一节就可以跳过了，只需要知道它对invalid的id的记录作了性能优化即可，它本质上也还是个Set集合，知道这一点就足够了。

而如果你对这个数据结构感兴趣，那我们接着往下看看SnapshotIdSet这个数据结构本身。

SnapshotIdSet是一个Set，这个Set专门用于记录“位”。它记录了从第lowerBound（`即下界`）位开始，往后\[0,127]个位的值（`即0或1`）。此外，它还稀疏记录了低于lowerBound的位值。而对于lowerBound+127位以上的位，不记录。

> `说明：上面的[]表示数学中的左闭右闭区间，下同。`

什么意思呢？看图。

![SnapshotIdSet.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/862e85b57810444fa53fec44295a3a39~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=1399&h=289&s=28059&e=png&b=ffffff)
这个Set的构造函数传入了四个参数：

*   `val upperSet: Long`：从lowerBound位开始，往后的 \[64,127] 位的值
*   `val lowerSet: Long`：从lowerBound位开始，往后的 \[0,63] 位的值
*   `val lowerBound: Int`：即下界，表示了这个SnapshotIdSet的下界是第多少位
*   `val belowBound: IntArray?`：提供了低于lowerBound的位的稀疏访问功能

结合图和构造函数中传入的参数，我们再解释一下。

这个结构就是对从第lowerBound位开始往后\[0,127]位提供快速访问的一个类。从lowerBound+0\~lowerBound+63位，存在lowerSet这个Long中（`一个Long是64位`），类似地，从lowerBound+64\~lowerBound+127位，存在upperSet这个Long中。这些位的值要么是0，要么是1。由于这样的存储方式，当我们访问（`get`）、清除（`clear`）和设置（`set`）第\[lowerBound, lowerBound+127]位时，是非常快速的，就是O(1)的复杂度。

而除此以外，对于低于lowerBound的位置，这个数据结构也提供了访问，只不过是以稀疏的方式，也就是构造函数中的belowBound数组。

> **稀疏访问**
> 
> 当一个数据结构中，有大量的0值存在时，我们可以以稀疏的方式记录这个数据结构，以节省访问的时间和空间。例如，对于下面这个二维数组，当我们知道它很可能有大量0值时，我们记录它就不用全部记录了，而是只记录存在的值。
> ```
>      array     row col value  size 4,4
>     0 0 0 0     1   0    1
>     1 0 0 0     2   2    4
>     0 0 4 0     3   2    3
>     0 0 3 0    
> 
>     //这样我们只需要记右边的row、col、对应的value、整个数组的size
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

好了，SnapshotIdSet这个结构就说到这里。我们现在可以总结一下了，它就是用于记录invalid的Snapshot的id的，这个结构对于从lowerBound往后0~127个位置的get、set和clear都有着O(1)的访问时间复杂度，因此非常适合用于记录全局连续递增的Snapshot的id值。

### \[undefinedChapter].2 SnapshotDoubleIndexHeap

下面我们来看第二个数据结构，叫SnapshotDoubleIndexHeap。说是数据结构，这个类其实更偏向于代表一种算法。

这个类记录了一堆int值，调用lowestOrDefault方法则始终返回这些int值中的最小值。它能以O(1)的速度返回最小值。而往这个类中添加和删除int值的代价则最差情况下是O(logN)。

所以说这个类更倾向于是一种算法。

那么，Snapshot系统用这个类来跟踪所有固定快照id中的最小值。固定快照id要么是它invalid列表中的最低快照id，而当它的invalid列表为空时，则是它自己的id。

好了，固定快照是什么东西我们放在后面再去讨论，现在，与上个小节一样，我们先来看看这个SnapshotDoubleIndexHeap的本身，是怎么实现这样快速访问最小值的，对此不感兴趣的也可以跳过了。

呃，好吧，别看了，它没什么好说的，它就是一个**堆排序**，对此不熟悉的话，可以自己去搜搜堆排序的算法详解，我们在这里就不去花篇幅讲堆排序了。

只不过，它是在每次新增和删除int值后，都会对int堆进行调整，保证它的有序性，因此增和删最坏情况下会耗时O(logN)，而这样，访问时就可以直接访问已经处于有序状态的int堆了，因此访问最小值的开销就是访问一个数组元素的开销O(1)，没什么玄乎的。

至于这个类的名字，老长老长了，叫DoubleIndexHeap，Heap的意思是堆，表示int值构成了一个堆，采用堆排序；而DoubleIndex的意思是，为了记录这些int值的位置，还需要再用另一组IntArray对这一组values的位置进行跟踪记录，具体的我们就不再分析了。

总之这个类，或者说算法，就是一个堆排序的思想，最终实现了O(1)复杂度的最小int值访问。

接下来我们回到主线，继续分析Snapshot系统。