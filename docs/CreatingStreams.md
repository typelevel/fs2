# Creating Streams

FS2 is built upon `Stream[+F[_], +O]`, which is essentially a producer of `O`s which may evaluate effects of type `F`. For example
```scala
import fs2._
// import fs2._

import fs2.util.Task
// import fs2.util.Task

val ones = Stream.iterate(1)(identity)
// ones: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@7a802fd
```
This creates a `Stream[Nothing,Int]`, which is a _pure_ stream, meaning its sole purpose is to provide an infinite stream of *1*s. However, this definition using `iterate` and `identity` obscures the intent of this code, but thankfully there's the helper function `constant` which produces the identical result.
```scala
val ones = Stream.constant(1)
// ones: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@8f3b57e
```

What about producing all ints between 0 and 100? We could use the same `iterate` approach with an increment function like this:
```scala
val zeroTo100 = Stream.iterate(0)(_ + 1).take(101)
// zeroTo100: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@4fc87c18
```
That's reasonably straightforward, our Stream begins at *0* and adds one to the previous value at each step. We then take 101 elements from this stream (because we included 0), which means `zeroTo100` is no longer an infinite stream. What happens if we try to take more than 101 elements from `zeroTo100`?
```scala
val hmmm = zeroTo100.take(1000).toList.length
// hmmm: Int = 101
```
As you'd expect, `hmm` is 101 elements long. But the initial creation of `zeroTo100` is pretty ugly and ranging over the integers is fairly common, so there is the `range` function, which allows you to generate finite ranges with an optional step-size, but only incrementing.
```scala
val zeroTo100 = Stream.range(0,101)
// zeroTo100: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@1a146eb1

val evensTo100 = Stream.range(1,101,2)
// evensTo100: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@14a5d5da
```

## Evaluating Tasks
In addition to creating pure streams using some generative function, we can also create streams by evaluating an effect, `F[A]`. The resulting stream will emit the `A` or fail attempting to do so.
```scala
val greeting = Stream.eval(Task.now("Hi there!"))
// greeting: fs2.Stream[fs2.util.Task,String] = fs2.Stream$$anon$1@7b19e2fd

val hi = greeting.runLog.run.unsafeRun
// hi: Vector[String] = Vector(Hi there!)
```
This producees a `Stream[Task, String]`, which we can then force evaluation of using the `runLog.run.unsafeRun`. This stack of `run` calls peels back the layers from `Stream` to `Task`  to `A`, which in this example is a `Vector[String]`.

Because `greeting` is a `Stream`, we can use all sorts of great stream operators on it
```scala
greeting.repeat //analogous to constant above
// res0: fs2.Stream[fs2.util.Task,String] = fs2.Stream$$anon$1@657df141

val goodbye = Stream.eval(Task.now("Goodbye..."))
// goodbye: fs2.Stream[fs2.util.Task,String] = fs2.Stream$$anon$1@424b10a

val hiBye = (greeting ++ goodbye) // concatenates the two streams
// hiBye: fs2.Stream[fs2.util.Task,String] = fs2.Stream$$anon$1@7ee377

hiBye.runLog.run.unsafeRun
// res1: Vector[String] = Vector(Hi there!, Goodbye...)
```

The `repeat` operator repeats the current stream once the end has been reached. Repeat is effectively a no-op for infinite streams
```scala
val N = Stream.iterate(0)(_ + 1)
// N: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@60a2848a

N.take(10).toList
// res2: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

N.repeat.take(20).toList
// res3: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19)
```
