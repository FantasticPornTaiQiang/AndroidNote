## 写在开头

> `本篇是深入探索Compose原理系列的开篇。`

Compose作为官方推行的高性能声明式UI框架，别看我们用它的时候就只是寥寥几行代码，用起来非常简单，实际上是它为在幕后为我们做了无比复杂的工作，这才使得我们能够以简洁的写法，实现UI页面和UI刷新。

那么，你是否好奇Compose框架背后的原理呢？

这个系列就是想探索一下Compose框架背后繁杂的工作，以及，从中，我们能学习一些设计思想和代码技术细节。在看完这个系列之后，希望你能有这些收获：
- Compose框架的设计思想、实现原理
- 代码写法、实现细节
- 读源码的能力、分析思考总结能力

在开始看之前，请确定你已经熟练掌握kotlin的常用语法和Kotlin式的编程风格，并且能熟练使用Compose，知道Compose中常见的概念。（`或者边看边学习`）

那么接下来，我们的旅程正式开始。

> 这个系列主要探究Compose Runtime，对于UI和Compiler层，目前计划是不会过多涉及。

## 0 探索方法论

Compose框架太庞大了，面对这样一个庞然大物，我们要先说好我们的探索方式。我们不能像无头苍蝇一样乱撞。

我总结了一个我认为是比较好的探索方式——我们将以一个类似“递归”的方式探索。具体地，对于一个大的概念的探索，分为三大步、五小步：

**第一步 准备工作**

**1. 首先，我们需要先对它有一个大致的、最初步的认知（`比较好的方式是举例子`）。**
   - 例如，现在我们要理解SlotTable，我们需要先大致知道它是个啥，好，现在我告诉你，SlotTable就是Compose框架存放运行时各种数据的地方，比如你在@Composable中写的remember的状态就存放于SlotTable中。这就是我所说的大致认知。
   - 这一步非常重要，因为在探索Compose的设计和实现细节时，我们会经常遇到这样的新的概念，如果你在理解这个新的概念之前，对于它本身是干什么的没有任何了解，这无疑是灾难。而如果你在深入之前，对这个概念有个大概认知，那这就会在无形之中带给你一些方向感。
   - 对于这一步，一般情况下，我会直接告诉你关于这个概念的大致认知。我会通过举例子、淡化细节、介绍应用场景等方式来尽量快速地让你知道这个概念是为了什么样的目的而被设计出来的。

**2. 然后，很自然而然地，你可能会对此产生很多疑问。所以下一小步，就是提出疑问。**
   - 例如，现在我们大致知道，SlotTable是存放运行时数据的，那么很自然地，会冒出很多疑问，比如，具体存放了哪些运行时数据？如何存放？采用了什么数据结构？怎么样保证高性能...等等。
   - 这一步非常重要，既然是“探索”，而非填鸭式地被灌输知识，那么，这些疑问就是驱动我们寻找答案的动力，也是我们最终的目标。换句话说，什么时候我们能说我们已经掌握这个概念了？自然是不再有任何疑问了的时候。
   - 对于这一步，一般情况下，我会直接提出一些疑问，这些疑问会在接下来的“递归”过程中逐渐得到解决，但同时，在递归探索过程中可能又会产生新的疑问，那么，我们需要时刻保持清醒，明确当前探索的“主线任务”和“支线任务”，不要混乱。

**第二步 “递归”之“递”**

**3. 然后，我们开始“递归”中“递”的过程，即，理解它的子概念，包括属性、函数签名、子概念等。**
   - 例如，现在我们知道SlotTable是存运行时数据的，并且对此产生了很多疑问，我们要往下深入。那么，SlotTable作为一个类，首先应该去尝试理解它的属性、函数签名等，并且此时很可能遇到新的子概念，比如SlotTable中有Group、Slot，还有Gap、Address、Index、Anchor等等子概念，我们要想办法去对它们有一个大概认知。又例如，SlotTable其实也是一个文件，叫SlotTable.kt，那么读文件的时候也可以按这种方式，先去看一看这个文件有哪些类、常量、子概念、函数签名等。
   - 这一步非常重要，因为想要一口气拿下这个概念本身，很可能非常有难度，因此，拆分下去，先去理解属性、函数签名、子概念等，是一个很好的做法。
   - 对于这一步，其实并不需要完全去理解，只需要有一个大概认知即可。比如对于属性，我们可以通过注释看看它是用于什么的，如果没有注释，也可以通过属性名大致猜测一下。又比如对于函数，我特意强调的是“函数签名”，而不是“函数实现”，即，可以去关注一下函数的注释、名称（`像这种源码名称都不会乱起的，从名称往往就可以获得很多信息`）、签名（`参数、返回值等`）、调用（`调用者、调用处等`）等等，换言之，我们也只是先瞟一眼有哪些函数，以及它们大致的用途。

**4. 接下来，我们要去分析它的行为。即，搞清楚它的原理、流程、设计思想、实现方案等等。**
   - 例如，现在我们大致理解了SlotTable里面的一些概念，并且知道它含有一些基本的增删改查、移动Gap等方法，那接下来就应该去搞清楚这些函数的实现，并尝试去了解SlotTable的其它运作原理和流程，例如startGroup、endGroup等，在这之中，我们又会遇到新的概念——Composer。
   - 这一步其实就是在探索实现过程、分析行为、总结原理了。在这一步中，能解决一部分我们之前提出的疑问，同时很可能出现新的疑问。
   - 到这一步为止，都还是在探索这个概念本身。

**第三步 “递归”之“归”**

**5. 最后，根据我们分析和总结的对这个概念的理解，尝试向上探索，看看能否把若干个相关联概念串起来，归纳到更为庞大的体系中，进行更全面的总结。**
   - 例如，当我们把SlotTable本身了解的差不多了，就该往更大的层面看看了。即，我们要了解用于存放运行时数据的SlotTable是如何参与Compose的组合和重组的，这之中会遇到Composer、Composition、Recomposer等等新角色。这就是“归”的过程，我们尝试在理解SlotTable的大致逻辑和操作之后，把它“归”入更大的体系中去。
   - 这一步就是最后一步了。随着不断地归纳，我们最终能解决所有的疑惑——至少是大部分疑惑。

这三大步、五小步就是贯穿我们整个探索过程的探索方案了。我们会按照这样的思路，逐步揭开Compose框架的面纱。

当然，上面的方法论是最理想的状况，实际情况会非常复杂，可能并不会完全严格按照方法论进行探索。比如，对于一个概念，与之关联的可能有四五个新概念，这时候的“递”和“归”可能会非常难理顺，我们需要根据实际的情况作出一些战略调整。这是对我们的一个挑战。

那么接下来，按照我们的探索方法论，我们对整个Compose框架的原理实施第一步，即，下面我们先来大致、初步了解一下Compose框架的整个运作原理。这其中出现的概念也将是我们之后具体分析的一个大致路线。

## 1 Compose原理初窥

按照方法论说的，要了解Compose的原理，我们首先得了解它大致如何运作，也就是有一个大致印象，包括对其子概念有一个大致印象。因此这一节，我们将概括性地讲清楚Compose框架

### 1.1 设计目标

Compose是个UI框架，那么，一切从UI开始。我们思考一下这么一个UI框架所应具备的能力，以及对应的实现方案。

对于一个**UI框架**，最核心的功能有两个：展示UI和更新UI。

进一步地，对于一个优秀的**声明式UI框架**，它需要：

- **在写法上，以简洁直观见长**，能够直接以代码反映出UI，用于展示UI的代码更像是“**UI的配置文件**”。
   - 也因此，UI对状态的依赖不再是命令式的，而是直接把状态内嵌在“UI配置”中，框架能直接根据状态展示和更新UI。
- **在性能上，需要正确响应状态变化，并且高效完成UI的更新**。

除此以外，如果这个UI框架还能有一些其它的特性，那就是锦上添花了，例如：
- **与Android原生开发的互操作性。** 现在大部分Android原生应用，还是采用原本的命令式写法，已经成熟落地的App即使想引进新技术也一定是一点一点循序渐进的引入，那么新的框架如果能和原生兼容，能够互操作就更好了。
- **跨平台能力。** 如果说我们只写一套代码，但在Android、iOS、Windows、浏览器等不同平台都能运行，这也是很棒的一件事。
- **子线程更新UI。** 原生的Android开发一般情况下必须在主线程更新UI，而如果我们的UI框架能够完成子线程更新UI，那也是个额外的惊喜。（`事实上，是子线程更新状态，而Compose的UI依赖于状态，那么如果我们在子线程更新状态，UI也能变化，那就当作是子线程能更新UI了。实际上真正刷新UI的那一阶段还是在主线程的，只不过开发者不再需要去主动关注这一点了。`）

当然，还有一些后续的目标，例如能配置全局的主题样式、能方便地编写动画、有完善配套的开发者工具、调试工具、性能监测工具等等，这些都是基于前面设计的框架而衍生的后续的工作，我们暂且不把这些纳入主要目标。

好了，有了以上的目标，现在来具体设计我们的UI框架。

#### 1.1.1 编程语言

要想写法能简洁直观，首先要考虑的就是编程语言。编程语言很大程度上决定了写起来是否舒服。

好好好，那当然是Kotlin了，它太强势了，用Kotlin作为编程语言，主要是这么几点好处：

- 如果UI也能用Kotlin写，不像之前原生要写xml，那简直能够**和业务逻辑无缝衔接**。
- Kotlin本身太强大了，除了语法简洁API众多、空安全、协程、Flow等，最重要的还是它的**函数、lambda**写法，这种编程方式并不是Kotlin首创的，但真的感觉Kotlin把函数和lambda玩到了极致。

#### 1.1.2 写法

好，现在我们确定了Kotlin作为编程语言，那么接下来就该思考，如何用Kotlin设计这样一套声明式UI框架了。

明确一下具体的目标，目标有三：简洁直观、能够包含状态、能处理所有的UI场景。

**简洁直观**

现在我们想展示一个文本和一个按钮，那么，如果我的代码长这样，够不够简洁直观？

```kotlin
@Composable
fun HelloWorld() {
    Text("Hello: ptq")

    Button(onClick = { /** onClick */ }) {
        Text("Hello ptq")
    }
}
```

**能够包含状态**

现在，我们想要在上面的基础上，做一个计数器，也就是UI包含状态，并且，状态变化能导致UI更新。那么，如果我的代码长这样，是不是在简洁的基础上，又能包含状态？

```kotlin
@Composable
fun HelloWorld() {
    var count by remember { mutableStateOf(0) }

    Button(onClick = { count++ }) {
        Text("Hello: $count")
    }
}
```

**能处理所有的UI场景**

这一部分我想不到什么好的概括。大致意思就是，我们在写UI的时候，会遇到各种各样的情形，例如一些层级嵌套，例如一些逻辑代码。那么，如果我们的代码长下面这样，是不是自由度非常高？

例如，当需要自由嵌套时：

（`利用“kotlin的函数的最后一个参数是函数时，可以提到小括号外面”这一写法特性，看起来更舒适了。`）

```kotlin
@Composable
fun FreeView() {
    Column {
        Text("Hello, ptq")
        Row {
            Button(onClick = { /*TODO*/ }) {
                Text("Click Me")
            } 
        }
        
        Card {
            Text("ppptq")
        }
    }
}
```

例如，当需要if/when等条件判断时：

```kotlin
@Composable
fun IfView() {
    var show by remember { mutableStateOf(false) }
    var showWhat by remember { mutableStateOf("text") }

    if (show) {
        when(showWhat) {
            "text" -> Text("Hello: ptq")
            "image" -> Image(painter = painterResource(id = R.drawable.mtrl_dropdown_arrow), contentDescription = null)
            "button" -> Button(onClick = { /*TODO*/ }) {
                Text("click me")
            }
        }
    }
}
```

例如，当需要使用for循环时：
```kotlin
@Composable
fun ForView(userInfoList: Array<String>) {
    userInfoList.forEach { user ->
        Card {
            Text(user)
        }
    }
}
```

此外，这样的编程风格也非常贴合原本的kotlin编程习惯，而不用再去定义一套新的写法体系。

好了，以上这些就是我们最基本的一个设计目标了。我们需要把框架做成最终能让开发者以上面的方式进行UI编写，那么如何做到呢？

### 1.2 实现方案

接下来我们逐步来实现我们上面的设计，这一部分会细化很多，毕竟上面的设计太过于粗糙，距离能面对复杂多样的生产需求还很远。

这一节中，我们会提到许多概念，这些概念都是这个系列文章之后会去具体分析的。

#### 1.2.1 核心思想

Compose框架思想的核心是：**函数是一个可重复执行的代码块对象。**

贯穿整个Kotlin的重要思想之一，函数，或者Lambda，或者接口，本质上都是一段可执行代码块，我们也可以把它们当一个对象看，它们具有的功能就是“invoke”，也即调用、执行之前写好的代码。

那么能否在我们的UI框架中充分利用这一点呢？当然。

我们让开发者把UI写在函数里，当需要嵌套UI时，直接调用函数；当需要条件、循环等逻辑时，也是直接调用函数；当需要设置UI的属性时，直接给函数传参；需要回调（例如onClick）时，也是直接函数传参；这样基本上所有的场景都能解决了。

为了标识哪些函数是属于我们UI框架的，我们要求开发者给这些函数打上@Composable这个记号。

那么，之前提到的状态更新呢？毫无疑问，如果采用函数调用的方式，我们直接把函数再调用一遍，就能更新UI了。

再说具体些，下面这段UI代码，第一次调用HelloWorld，传入count=1，那就显示1，那假设我们的状态count变成了2，那只要再调用一遍这个HelloWorld函数，传入count=2，自然就显示了2。

```kotlin
@Composable
fun HelloWorld(count: Int) {
   Text("Hello: $count")
}
```

所以，我们需要有印象的第一个重点就是：**Compose框架的状态更新是通过再次调用@Composable函数完成的。**

而如何能够再次调用这个函数？那我们当然要把这个函数当作一个对象暂存起来，在有状态发生变化的时候，去重新调用它，这样，就完成了UI的更新。

> `对应到源码中，ComposableLambdaImpl(.kt)就是我们所说的加了@Composable注解的函数的对象，具体的实现在对应jvm平台的ComposableLambdaImpl(.jvm.kt)内。`

有了这一核心思想，我们来看其他各个设计目标如何来实现。

#### 1.2.2 状态管理

我们先来看状态管理，状态管理主要分为状态的**感知**和**记忆**。这一部分的内容我在之前的一篇文章的第一节和第二节里有更详细的的说明，这里我就简要概括一下。（[传送门](https://juejin.cn/post/7228959271605780540#heading-0)）

以下面这段代码为例。

```kotlin
@Composable
fun HelloWorld() {
    var count by remember { mutableStateOf(0) }
    var otherValue = 0

    Button(onClick = { count++; otherValue++ }) {
        Text("Hello: $count $otherValue")
    }
}
```

**状态感知**

我们的状态count写在一个@Composable函数里，但假如说count就只是一个普通的变量，count变化了会触发Text的UI更新，那就完蛋了——我们函数里面任何被UI依赖的变量都可能触发UI的刷新，这肯定不是我们期望的。

我们期望的是，框架能够区分UI依赖的“状态”和普通的变量，仅感知状态的变化。

因此，状态就已经不再是一个普通的变量了。而是一个专门的类（`接口`），叫State。只有State的变化才能被框架感知。

**状态记忆**

我们的UI是写在@Composable函数里的，尽管它有@Composable注解，但是本质上还是个函数，状态本质上也还是个变量。那么，如果我们的状态不存放在别的地方，随着函数执行完毕，状态变量就会丢失。因此，我们的状态管理设计需要让状态能够跨越函数的作用域而持久存在。为此，我们设计了remember函数，记住这个State。

换言之，上面的代码中，运行到`var count by remember { mutableStateOf(0) }`这一句时，就会把count的值存下来，如果count的值改变了，也会存下来。因此之后如果函数再次执行到这里，就会取出存放在这个@Composable函数之外的count值，也就实现了状态的记忆。

##### 1.2.2.1 Snapshot

接下来我们思考一下状态应该如何实现。

状态需要具备的能力主要有两点：感知写入变化，以及，之前在1.1节提到的子线程能更新它。

于是我们设计了Snapshot系统完成上述目标。

Snapshot系统

##### 1.2.3 


主要分为三部分工作，分别是：

1. 在App编译期，Compose编译器负责生成并插入代码。
2. 在App运行时，Compose Runtime负责调度管理UI的层级结构和状态，即**组合**。
3. 在App运行时，Compose UI负责具体的UI显示，即**布局**和**绘制**。

若UI需要更新，即触发UI层级结构和状态的改变，也就是重新组合，这就是**重组**。

下面我们展开说说。

Compose框架在App编译期会生成并插入一些代码。这些代码主要用于把

## 2 提出疑问

## 3 导航

## 4 对本系列的Q&A

1. 本篇涉及编译器的源码解读吗？
2. 本篇读完以后，就完全掌握了Compose的原理吗？

## 5 小结一下

















