package fs2
package util

import fs2.internal.{Actor,Future,LinkedMap}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ScheduledExecutorService, ConcurrentLinkedQueue, ExecutorService, Executors}
import scala.concurrent.duration._
import scala.collection.immutable.Queue

/*
`Task` is a trampolined computation producing an `A` that may
include asynchronous steps. Arbitrary monadic expressions involving
`map` and `flatMap` are guaranteed to use constant stack space.
In addition, using `Task.async`, one may construct a `Task` from
callback-based APIs. This makes `Task` useful as a concurrency primitive
and as a control structure for wrapping callback-based APIs with a more
straightforward, monadic API.

Task is also exception-safe. Any exceptions raised during processing may
be accessed via the `attempt` method, which converts a `Task[A]` to a
`Task[Either[Throwable,A]]`.

Unlike the `scala.concurrent.Future` type introduced in scala 2.10,
`map` and `flatMap` do NOT spawn new tasks and do not require an implicit
`ExecutionContext`. Instead, `map` and `flatMap` merely add to
the current (trampolined) continuation that will be run by the
'current' thread, unless explicitly forked via `Task.start` or
`Future.apply`. This means that `Future` achieves much better thread
reuse than the 2.10 implementation and avoids needless thread
pool submit cycles.

`Task` also differs from the `scala.concurrent.Future` type in that it
does not represent a _running_ computation. Instead, we
reintroduce concurrency _explicitly_ using the `Task.start` function.
This simplifies our implementation and makes code easier to reason about,
since the order of effects and the points of allowed concurrency are made
fully explicit and do not depend on Scala's evaluation order.
*/
class Task[+A](val get: Future[Either[Throwable,A]]) {

  def flatMap[B](f: A => Task[B]): Task[B] =
    new Task(get flatMap {
      case Left(e) => Future.now(Left(e))
      case Right(a) => Task.Try(f(a)) match {
        case Left(e) => Future.now(Left(e))
        case Right(task) => task.get
      }
    })

  def map[B](f: A => B): Task[B] =
    new Task(get map { _.right.flatMap { a => Task.Try(f(a)) } })

  /** 'Catches' exceptions in the given task and returns them as values. */
  def attempt: Task[Either[Throwable,A]] =
    new Task(get map {
      case Left(e) => Right(Left(e))
      case Right(a) => Right(Right(a))
    })

  /**
   * Returns a new `Task` in which `f` is scheduled to be run on completion.
   * This would typically be used to release any resources acquired by this
   * `Task`.
   */
  def onFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    new Task(get flatMap {
      case Left(e) => f(Some(e)).get flatMap { _ => Future.now(Left(e)) }
      case r => f(None).get flatMap { _ => Future.now(r) }
    })

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function, calling Task.now on the result. Any nonmatching exceptions
   * are reraised.
   */
  def handle[B>:A](f: PartialFunction[Throwable,B]): Task[B] =
    handleWith(f andThen Task.now)

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function. Any nonmatching exceptions are reraised.
   */
  def handleWith[B>:A](f: PartialFunction[Throwable,Task[B]]): Task[B] =
    attempt flatMap {
      case Left(e) => f.lift(e) getOrElse Task.fail(e)
      case Right(a) => Task.now(a)
    }

  /**
   * Runs this `Task`, and if it fails with an exception, runs `t2`.
   * This is rather coarse-grained. Use `attempt`, `handle`, and
   * `flatMap` for more fine grained control of exception handling.
   */
  def or[B>:A](t2: Task[B]): Task[B] =
    new Task(this.get flatMap {
      case Left(e) => t2.get
      case a => Future.now(a)
    })

  /**
   * Run this `Task` and block until its result is available. This will
   * throw any exceptions generated by the `Task`. To return exceptions
   * in an `\/`, use `attemptRun`.
   */
  def run: A = get.run match {
    case Left(e) => throw e
    case Right(a) => a
  }

  /** Like `run`, but returns exceptions as values. */
  def attemptRun: Either[Throwable,A] =
    try get.run catch { case t: Throwable => Left(t) }

  /**
   * Run this computation to obtain either a result or an exception, then
   * invoke the given callback. Any pure, non-asynchronous computation at the
   * head of this `Future` will be forced in the calling thread. At the first
   * `Async` encountered, control is transferred to whatever thread backs the
   * `Async` and this function returns immediately.
   */
  def runAsync(f: Either[Throwable,A] => Unit): Unit =
    get.runAsync(f)

  /**
   * Run this computation and return the result as a standard library `Future`.
   * Like `runAsync` but returns a standard library `Future` instead of requiring
   * a callback.
   */
  def runAsyncFuture: scala.concurrent.Future[A] = {
    val promise = scala.concurrent.Promise[A]
    runAsync { e => e.fold(promise.failure, promise.success); () }
    promise.future
  }

  /**
   * Run this computation fully asynchronously to obtain either a result
   * or an exception, then invoke the given callback. Unlike `runAsync`,
   * all computations are executed asynchronously, whereas `runAsync` runs
   * computations on callers thread until it encounters an `Async`.
   */
  def runFullyAsync(f: Either[Throwable,A] => Unit)(implicit S: Strategy): Unit =
    Task.start(this).flatMap(identity).runAsync(f)

  /**
   * Run this computation fully asynchronously and return the result as a
   * standard library `Future`. Like `runFullyAsync` but returns a
   * standard library `Future` instead of requiring a callback.
   */
  def runFullyAsyncFuture(implicit S: Strategy): scala.concurrent.Future[A] =
    Task.start(this).flatMap(identity).runAsyncFuture

  /**
   * Run this `Task` and block until its result is available, or until
   * `timeoutInMillis` milliseconds have elapsed, at which point a `TimeoutException`
   * will be thrown and the `Future` will attempt to be canceled.
   */
  def runFor(timeoutInMillis: Long): A = get.runFor(timeoutInMillis) match {
    case Left(e) => throw e
    case Right(a) => a
  }

  def runFor(timeout: Duration): A = runFor(timeout.toMillis)

  /**
   * Like `runFor`, but returns exceptions as values. Both `TimeoutException`
   * and other exceptions will be folded into the same `Throwable`.
   */
  def attemptRunFor(timeoutInMillis: Long): Either[Throwable,A] =
    get.attemptRunFor(timeoutInMillis).right flatMap { a => a }

  def attemptRunFor(timeout: Duration): Either[Throwable,A] =
    attemptRunFor(timeout.toMillis)

  /**
   * A `Task` which returns a `TimeoutException` after `timeoutInMillis`,
   * and attempts to cancel the running computation.
   */
  def timed(timeoutInMillis: Long)(implicit S: ScheduledExecutorService): Task[A] =
    new Task(get.timed(timeoutInMillis).map(_.right.flatMap(x => x)))

  def timed(timeout: Duration)(implicit S: ScheduledExecutorService): Task[A] =
    timed(timeout.toMillis)

  /**
   Ensures the result of this Task satisfies the given predicate,
   or fails with the given value.
   */
  def ensure(failure: => Throwable)(f: A => Boolean): Task[A] =
    flatMap(a => if(f(a)) Task.now(a) else Task.fail(failure))

  /**
   Returns a `Task` that, when run, races evaluation of `this` and `t`,
   and returns the result of whichever completes first. The losing task
   continues to execute in the background though its result will be sent
   nowhere.
   */
  def race[B](t: Task[B])(implicit S: Strategy): Task[Either[A,B]] = {
    Task.ref[Either[A,B]].flatMap { ref =>
      ref.setRace(this map (Left(_)), t map (Right(_)))
          .flatMap { _ => ref.get }
    }
  }

  /** Create a `Task` that will evaluate `a` after at least the given delay. */
  def schedule(delay: Duration)(implicit pool: ScheduledExecutorService): Task[A] =
    Task.schedule((), delay) flatMap { _ => this }
}

object Task extends Instances {

  type Callback[A] = Either[Throwable,A] => Unit

  /** A `Task` which fails with the given `Throwable`. */
  def fail(e: Throwable): Task[Nothing] = new Task(Future.now(Left(e)))

  /** Convert a strict value to a `Task`. Also see `delay`. */
  def now[A](a: A): Task[A] = new Task(Future.now(Right(a)))

  /**
   * Promote a non-strict value to a `Task`, catching exceptions in
   * the process. Note that since `Task` is unmemoized, this will
   * recompute `a` each time it is sequenced into a larger computation.
   * Memoize `a` with a lazy value before calling this function if
   * memoization is desired.
   */
  def delay[A](a: => A): Task[A] = suspend(now(a))

  /**
   * Produce `f` in the main trampolining loop, `Future.step`, using a fresh
   * call stack. The standard trampolining primitive, useful for avoiding
   * stack overflows.
   */
  def suspend[A](a: => Task[A]): Task[A] = new Task(Future.suspend(
    Try(a.get) match {
      case Left(e) => Future.now(Left(e))
      case Right(f) => f
  }))

  /** Create a `Future` that will evaluate `a` using the given `Strategy`. */
  def apply[A](a: => A)(implicit S: Strategy): Task[A] =
    new Task(Future(Try(a)))

  /**
   * Don't use this. It doesn't do what you think. If you have a `t: Task[A]` you'd
   * like to evaluate concurrently, use `Task.start(t) flatMap { ft => ..}`.
   */
  @deprecated(message = "use `Task.start`", since = "0.8")
  def fork[A](a: => Task[A])(implicit S: Strategy): Task[A] =
    apply(a) flatMap { a => a }

  /**
   Given `t: Task[A]`, `start(t)` returns a `Task[Task[A]]`. After `flatMap`-ing
   into the outer task, `t` will be running in the background, and the inner task
   is conceptually a future which can be forced at any point via `flatMap`.

   For example:

   {{{
     for {
       f <- Task.start { expensiveTask1 }
       // at this point, `expensive1` is evaluating in background
       g <- Task.start { expensiveTask2 }
       // now both `expensiveTask2` and `expensiveTask1` are running
       result1 <- f
       // we have forced `f`, so now only `expensiveTask2` may be running
       result2 <- g
       // we have forced `g`, so now nothing is running and we have both results
     } yield (result1 + result2)
   }}}
  */
  def start[A](t: Task[A])(implicit S: Strategy): Task[Task[A]] =
    ref[A].flatMap { ref => ref.set(t) map (_ => ref.get) }

  /**
   Like [[async]], but run the callback in the same thread in the same
   thread, rather than evaluating the callback using a `Strategy`.
   */
  def unforkedAsync[A](register: (Either[Throwable,A] => Unit) => Unit): Task[A] =
    async(register)(Strategy.sequential)

  /**
   Create a `Task` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version. The callback is run using the strategy `S`.
   */
  def async[A](register: (Either[Throwable,A] => Unit) => Unit)(implicit S: Strategy): Task[A] =
    new Task(Future.async(register)(S))

  /** Create a `Task` that will evaluate `a` after at least the given delay. */
  def schedule[A](a: => A, delay: Duration)(implicit pool: ScheduledExecutorService): Task[A] =
    new Task(Future.schedule(Try(a), delay))

  /** Utility function - evaluate `a` and catch and return any exceptions. */
  def Try[A](a: => A): Either[Throwable,A] =
    try Right(a) catch { case e: Throwable => Left(e) }

  def TryTask[A](a: => Task[A]): Task[A] =
    try a catch { case e: Throwable => fail(e) }

  private trait MsgId
  private trait Msg[A]
  private object Msg {
    case class Read[A](cb: Callback[(A, Long)], id: MsgId) extends Msg[A]
    case class Nevermind[A](id: MsgId, cb: Callback[Boolean]) extends Msg[A]
    case class Set[A](r: Either[Throwable,A]) extends Msg[A]
    case class TrySet[A](id: Long, r: Either[Throwable,A],
                         cb: Callback[Boolean]) extends Msg[A]
  }

  def ref[A](implicit S: Strategy): Task[Ref[A]] = Task.delay {
    var result: Either[Throwable,A] = null
    // any waiting calls to `access` before first `set`
    var waiting: LinkedMap[MsgId, Callback[(A, Long)]] = LinkedMap.empty
    // id which increases with each `set` or successful `modify`
    var nonce: Long = 0

    lazy val actor: Actor[Msg[A]] = Actor.actor[Msg[A]] {
      case Msg.Read(cb, idf) =>
        if (result eq null) waiting = waiting.updated(idf, cb)
        else { val r = result; val id = nonce; S { cb(r.right.map((_,id))) } }

      case Msg.Set(r) =>
        if (result eq null) {
          nonce += 1L
          val id = nonce
          waiting.values.foreach(cb => S { cb(r.right.map((_,id))) })
          waiting = LinkedMap.empty
        }
        result = r

      case Msg.TrySet(id, r, cb) =>
        if (id == nonce) {
          nonce += 1L; val id2 = nonce
          waiting.values.foreach(cb => S { cb(r.right.map((_,id2))) })
          waiting = LinkedMap.empty
          result = r
          cb(Right(true))
        }
        else cb(Right(false))

      case Msg.Nevermind(id, cb) =>
        val interrupted = waiting.get(id).isDefined
        waiting = waiting - id
        S { cb (Right(interrupted)) }
    }

    new Ref(actor)
  }

  class Ref[A] private[fs2](actor: Actor[Msg[A]]) {

    def access: Task[(A, Either[Throwable,A] => Task[Boolean])] =
      getStamped(new MsgId {}).map { case (a, id) =>
        val set = (a: Either[Throwable,A]) =>
          Task.unforkedAsync[Boolean] { cb => actor ! Msg.TrySet(id, a, cb) }
        (a, set)
      }

    /**
     * Return a `Task` that submits `t` to this ref for evaluation.
     * When it completes it overwrites any previously `put` value.
     */
    def set(t: Task[A])(implicit S: Strategy): Task[Unit] =
      Task.delay { S { t.runAsync { r => actor ! Msg.Set(r) } }}
    def setFree(t: Free[Task,A])(implicit S: Strategy): Task[Unit] =
      set(t.run)
    def runSet(e: Either[Throwable,A]): Unit =
      actor ! Msg.Set(e)

    private def getStamped(msg: MsgId): Task[(A,Long)] =
      Task.unforkedAsync[(A,Long)] { cb => actor ! Msg.Read(cb, msg) }

    /** Return the most recently completed `set`, or block until a `set` value is available. */
    def get: Task[A] = getStamped(new MsgId {}).map(_._1)

    /** Like `get`, but returns a `Task[Unit]` that can be used cancel the subscription. */
    def cancellableGet: Task[(Task[A], Task[Unit])] = Task.delay {
      val id = new MsgId {}
      val get = getStamped(id).map(_._1)
      val cancel = Task.unforkedAsync[Unit] {
        cb => actor ! Msg.Nevermind(id, r => cb(r.right.map(_ => ())))
      }
      (get, cancel)
    }

    /**
     * Runs `t1` and `t2` simultaneously, but only the winner gets to
     * `set` to this `ref`. The loser continues running but its reference
     * to this ref is severed, allowing this ref to be garbage collected
     * if it is no longer referenced by anyone other than the loser.
     */
    def setRace(t1: Task[A], t2: Task[A]): Task[Unit] = Task.delay {
      val ref = new AtomicReference(actor)
      val won = new AtomicBoolean(false)
      val win = (res: Either[Throwable,A]) => {
        // important for GC: we don't reference this ref
        // or the actor directly, and the winner destroys any
        // references behind it!
        if (won.compareAndSet(false, true)) {
          val actor = ref.get
          ref.set(null)
          actor ! Msg.Set(res)
        }
      }
      t1.runAsync(win)
      t2.runAsync(win)
    }
  }
}

/* Prefer an `Async` and `Catchable`, but will settle for implicit `Monad`. */
private[fs2] trait Instances1 {
  implicit def monad: Catchable[Task] = new Catchable[Task] {
    def fail[A](err: Throwable) = Task.fail(err)
    def attempt[A](t: Task[A]) = t.attempt
    def pure[A](a: A) = Task.now(a)
    def bind[A,B](a: Task[A])(f: A => Task[B]): Task[B] = a flatMap f
  }
}

private[fs2] trait Instances extends Instances1 {

  implicit def asyncInstance(implicit S:Strategy): Async[Task] = new Async[Task] {
    type Ref[A] = Task.Ref[A]
    def suspend[A](a: => A): Task[A] = Task.delay(a)
    def access[A](r: Ref[A]) = r.access
    def set[A](r: Ref[A])(a: Task[A]): Task[Unit] = r.set(a)
    def runSet[A](r: Ref[A])(a: Either[Throwable,A]): Unit = r.runSet(a)
    def ref[A]: Task[Ref[A]] = Task.ref[A](S)
    override def get[A](r: Ref[A]): Task[A] = r.get
    def cancellableGet[A](r: Ref[A]): Task[(Task[A], Task[Unit])] = r.cancellableGet
    def setFree[A](q: Ref[A])(a: Free[Task, A]): Task[Unit] = q.setFree(a)
    def bind[A, B](a: Task[A])(f: (A) => Task[B]): Task[B] = a flatMap f
    def pure[A](a: A): Task[A] = Task.now(a)
    def fail[A](err: Throwable): Task[A] = Task.fail(err)
    def attempt[A](fa: Task[A]): Task[Either[Throwable, A]] = fa.attempt
  }
}
