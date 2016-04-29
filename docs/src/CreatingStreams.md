# Creating Streams

FS2 is built upon `Stream[+F[_], +O]`, which is essentially a producer of `O`s which may evaluate effects of type `F`. For example
```tut:book
import fs2._
import fs2.util.Task

val ones = Stream.iterate(1)(identity)
```
This creates a `Stream[Nothing,Int]`, which is a _pure_ stream, meaning its sole purpose is to provide an infinite stream of *1*s. However, this definition using `iterate` and `identity` obscures the intent of this code, but thankfully there's the helper function `constant` which produces the identical result.
```tut:book
val ones = Stream.constant(1)
```

What about producing all ints between 0 and 100? We could use the same `iterate` approach with an increment function like this:
```tut:book
val zeroTo100 = Stream.iterate(0)(_ + 1).take(101)
```
That's reasonably straightforward, our Stream begins at *0* and adds one to the previous value at each step. We then take 101 elements from this stream (because we included 0), which means `zeroTo100` is no longer an infinite stream. What happens if we try to take more than 101 elements from `zeroTo100`?
```tut:book
val hmmm = zeroTo100.take(1000).toList.length
```
As you'd expect, `hmm` is 101 elements long. But the initial creation of `zeroTo100` is pretty ugly and ranging over the integers is fairly common, so there is the `range` function, which allows you to generate finite ranges with an optional step-size, but only incrementing.
```tut:book
val zeroTo100 = Stream.range(0,101)
val evensTo100 = Stream.range(1,101,2)
```

## Evaluating Tasks
In addition to creating pure streams using some generative function, we can also create streams by evaluating an effect, `F[A]`. The resulting stream will emit the `A` or fail attempting to do so.
```tut:book
val greeting = Stream.eval(Task.now("Hi there!"))
val hi = greeting.runLog.run.unsafeRun
```
This producees a `Stream[Task, String]`, which we can then force evaluation of using the `runLog.run.unsafeRun`. This stack of `run` calls peels back the layers from `Stream` to `Task`  to `A`, which in this example is a `Vector[String]`.

Because `greeting` is a `Stream`, we can use all sorts of great stream operators on it
```tut:book
greeting.repeat //analogous to constant above
val goodbye = Stream.eval(Task.now("Goodbye..."))
val hiBye = (greeting ++ goodbye) // concatenates the two streams
hiBye.runLog.run.unsafeRun
```

The `repeat` operator repeats the current stream once the end has been reached. Repeat is effectively a no-op for infinite streams
```tut:book
val N = Stream.iterate(0)(_ + 1)
N.take(10).toList
N.repeat.take(20).toList
```
