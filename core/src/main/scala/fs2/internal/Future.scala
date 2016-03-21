package fs2.internal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Callable, TimeoutException, ScheduledExecutorService, TimeUnit}
import scala.concurrent.SyncVar
import scala.concurrent.duration._
import fs2.Strategy

// for internal use only!

private[fs2] sealed abstract
class Future[+A] {
  import Future._

  def flatMap[B](f: A => Future[B]): Future[B] = this match {
    case Now(a) => Suspend(() => f(a))
    case Suspend(thunk) => BindSuspend(thunk, f)
    case Async(listen) => BindAsync(listen, f)
    case BindSuspend(thunk, g) =>
      Suspend(() => BindSuspend(thunk, g andThen (_ flatMap f)))
    case BindAsync(listen, g) =>
      Suspend(() => BindAsync(listen, g andThen (_ flatMap f)))
  }

  def map[B](f: A => B): Future[B] =
    flatMap(f andThen (b => Future.now(b)))

  def listen(cb: A => Trampoline[Unit]): Unit =
    (this.step: @unchecked) match {
      case Now(a) => cb(a).run
      case Async(onFinish) => onFinish(cb)
      case BindAsync(onFinish, g) =>
        onFinish(x => Trampoline.delay(g(x)) map (_ listen cb))
    }

  def listenInterruptibly(cb: A => Trampoline[Unit], cancel: AtomicBoolean): Unit =
    this.stepInterruptibly(cancel) match {
      case Now(a) if !cancel.get => cb(a).run
      case Async(onFinish) if !cancel.get =>
        onFinish(a =>
          if (!cancel.get) cb(a)
          else Trampoline.done(()))
      case BindAsync(onFinish, g) if !cancel.get =>
        onFinish(x =>
          if (!cancel.get) Trampoline.delay(g(x)) map (_ listenInterruptibly (cb, cancel))
          else Trampoline.done(()))
      case _ if cancel.get => ()
    }

  @annotation.tailrec
  final def step: Future[A] = this match {
    case Suspend(thunk) => thunk().step
    case BindSuspend(thunk, f) => (thunk() flatMap f).step
    case _ => this
  }

  /** Like `step`, but may be interrupted by setting `cancel` to true. */
  @annotation.tailrec
  final def stepInterruptibly(cancel: AtomicBoolean): Future[A] =
    if (!cancel.get) this match {
      case Suspend(thunk) => thunk().stepInterruptibly(cancel)
      case BindSuspend(thunk, f) => (thunk() flatMap f).stepInterruptibly(cancel)
      case _ => this
    }
    else this

  def runAsync(cb: A => Unit): Unit =
    listen(a => Trampoline.done(cb(a)))

  def runAsyncInterruptibly(cb: A => Unit, cancel: AtomicBoolean): Unit =
    listenInterruptibly(a => Trampoline.done(cb(a)), cancel)

  def run: A = this match {
    case Now(a) => a
    case _ => {
      val latch = new java.util.concurrent.CountDownLatch(1)
      @volatile var result: Option[A] = None
      runAsync { a => result = Some(a); latch.countDown }
      latch.await
      result.get
    }
  }

  def runFor(timeoutInMillis: Long): A = attemptRunFor(timeoutInMillis) match {
    case Left(e) => throw e
    case Right(a) => a
  }

  def runFor(timeout: Duration): A = runFor(timeout.toMillis)

  def attemptRunFor(timeoutInMillis: Long): Either[Throwable,A] = {
    val sync = new SyncVar[Either[Throwable,A]]
    val interrupt = new AtomicBoolean(false)
    runAsyncInterruptibly(a => sync.put(Right(a)), interrupt)
    sync.get(timeoutInMillis).getOrElse {
      interrupt.set(true)
      Left(new TimeoutException())
    }
  }

  def attemptRunFor(timeout: Duration): Either[Throwable,A] = attemptRunFor(timeout.toMillis)

  def timed(timeoutInMillis: Long)(implicit S: ScheduledExecutorService): Future[Either[Throwable,A]] =
    //instead of run this though chooseAny, it is run through simple primitive,
    //as we are never interested in results of timeout callback, and this is more resource savvy
    async[Either[Throwable,A]] { cb =>
      val cancel = new AtomicBoolean(false)
      val done = new AtomicBoolean(false)
      try {
        S.schedule(new Runnable {
          def run() {
            if (done.compareAndSet(false,true)) {
              cancel.set(true)
              cb(Left(new TimeoutException()))
            }
          }
        }
        , timeoutInMillis, TimeUnit.MILLISECONDS)
      } catch { case e: Throwable => cb(Left(e)) }

      runAsyncInterruptibly(a => if(done.compareAndSet(false,true)) cb(Right(a)), cancel)
    } (Strategy.sequential)

  def timed(timeout: Duration)(implicit S: ScheduledExecutorService):
    Future[Either[Throwable,A]] = timed(timeout.toMillis)

  def after(t: Duration)(implicit S: ScheduledExecutorService): Future[A] =
    Future.schedule((), t) flatMap { _ => this }

  def after(millis: Long)(implicit S: ScheduledExecutorService): Future[A] =
    after(millis milliseconds)
}

private[fs2] object Future {
  case class Now[+A](a: A) extends Future[A]
  case class Async[+A](onFinish: (A => Trampoline[Unit]) => Unit) extends Future[A]
  case class Suspend[+A](thunk: () => Future[A]) extends Future[A]
  case class BindSuspend[A,B](thunk: () => Future[A], f: A => Future[B]) extends Future[B]
  case class BindAsync[A,B](onFinish: (A => Trampoline[Unit]) => Unit,
                            f: A => Future[B]) extends Future[B]

  /** Convert a strict value to a `Future`. */
  def now[A](a: A): Future[A] = Now(a)

  def delay[A](a: => A): Future[A] = Suspend(() => Now(a))

  def suspend[A](f: => Future[A]): Future[A] = Suspend(() => f)

  def async[A](listen: (A => Unit) => Unit)(implicit S: Strategy): Future[A] =
    Async((cb: A => Trampoline[Unit]) => listen { a => S { cb(a).run } })

  /** Create a `Future` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => A)(implicit S: Strategy): Future[A] = Async { cb =>
    S { cb(a).run }
  }

  /** Create a `Future` that will evaluate `a` after at least the given delay. */
  def schedule[A](a: => A, delay: Duration)(implicit pool: ScheduledExecutorService): Future[A] =
    apply(a)(Scheduled(delay))

  case class Scheduled(delay: Duration)(implicit pool: ScheduledExecutorService) extends Strategy {
    def apply(thunk: => Unit) = {
      pool.schedule(new Callable[Unit] {
        def call = thunk
      }, delay.toMillis, TimeUnit.MILLISECONDS)
      ()
    }
  }
}
