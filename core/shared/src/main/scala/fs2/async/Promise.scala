package fs2.async

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{catsSyntaxEither => _, _}
import cats.effect.{Effect, IO}

import fs2.Scheduler
import fs2.internal.{LinkedMap, Token}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import Promise._

// TODO scaladoc
final class Promise[F[_], A] private[fs2] (ref: Ref[F, State[A]])(implicit F: Effect[F], ec: ExecutionContext) {

  // TODO add implementation comment on why new Token is side effectful in this case
  // and the fact that suspend is roughly 2x faster than delay flatMap
  def get: F[A] = F.suspend {
    val id = new Token
    getOrWait(id)
  }

  // TODO I prefer to not have `setAsync`, and leave forking at call site,
  // since we can't exploit SyncRef lazy set directly
  // I've replaced any setAsyncPure in fs2 with fork(setSync), which would
  // probably reveal that there's a bunch of unnecessary forks that can be avoided
  //
  // NOTE: this differs in behaviour from the old Ref in that by the time readers are notified
  // the new value is already guaranteed to be in place.
  def setSync(a: A): F[Unit] = {
    def notifyReaders(r: State.Unset[A]): Unit =
      r.waiting.values.foreach { cb =>
        ec.execute { () => cb(a) }
      }

    ref.modify2 {
      case s @ State.Set(_) => s -> F.unit
      case u @ State.Unset(_) => State.Set(a) -> F.delay(notifyReaders(u))
    }.flatMap(_._2)
  }

  // TODO better name?
  def trySetSync(a: A): F[Boolean] = {
    def notifyReaders(r: State.Unset[A]): Unit =
      r.waiting.values.foreach { cb =>
        ec.execute { () => cb(a) }
      }

    ref.modify2 {
      case s @ State.Set(_) => s -> false.pure[F]
      case u @ State.Unset(_) => State.Set(a) -> F.delay(notifyReaders(u)).as(true)
    }.flatMap(_._2)
  }

  /** Like [[get]] but returns an `F[Unit]` that can be used to cancel the subscription. */
  def cancellableGet: F[(F[A], F[Unit])] = F.delay {
    val id = new Token
    val get = getOrWait(id)
    val cancel = ref.modify {
      case s @ State.Set(_) => s
      case State.Unset(waiting) => State.Unset(waiting - id)
    }.void

    (get, cancel)
  }

  def timedGet(timeout: FiniteDuration, scheduler: Scheduler): F[Option[A]] =
    cancellableGet.flatMap { case (g, cancelGet) =>
      scheduler.effect.delayCancellable(F.unit, timeout).flatMap { case (timer, cancelTimer) =>
        fs2.async.race(g, timer).flatMap(_.fold(a => cancelTimer.as(Some(a)), _ => cancelGet.as(None)))
      }
    }

  /**
   * Runs `f1` and `f2` simultaneously, but only the winner gets to
   * `set` to this `ref`. The loser continues running but its reference
   * to this ref is severed, allowing this ref to be garbage collected
   * if it is no longer referenced by anyone other than the loser.
   *
   * If the winner fails, the returned `F` fails as well, and this `ref`
   * is not set.
   */
  def race(f1: F[A], f2: F[A])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] = F.delay {
    val refToSelf = new AtomicReference(this)
    val won = new AtomicBoolean(false)
    val win = (res: Either[Throwable,A]) => {
      // important for GC: we don't reference this ref
      // or the actor directly, and the winner destroys any
      // references behind it!
      if (won.compareAndSet(false, true)) {
        res match {
          case Left(e) =>
            refToSelf.set(null)
            throw e
          case Right(v) =>
            val action = refToSelf.getAndSet(null).setSync(v)
            unsafeRunAsync(action)(_ => IO.unit)
        }
      }
    }

    unsafeRunAsync(f1)(res => IO(win(res)))
    unsafeRunAsync(f2)(res => IO(win(res)))
  }

  private def getOrWait(id: Token): F[A] = {
    def registerCallBack(cb: A => Unit): Unit = {
      def go = ref.modify2 {
        case s @ State.Set(a) => s -> F.delay(cb(a))
        case State.Unset(waiting) => State.Unset(waiting.updated(id, cb)) -> F.unit
      }.flatMap(_._2)

      unsafeRunAsync(go)(_ => IO.unit)
    }

    ref.get.flatMap {
      case State.Set(a) => F.pure(a)
      case State.Unset(_) => F.async(cb => registerCallBack(x => cb(Right(x))))
      }
  }
}
object Promise {
  def empty[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Promise[F, A]] =
    F.delay(unsafeCreate[F, A])

  // TODO inline?
  private[fs2] def unsafeCreate[F[_]: Effect, A](implicit ec: ExecutionContext): Promise[F, A] =
    new Promise[F, A](new Ref(new AtomicReference(Promise.State.Unset(LinkedMap.empty))))

  private[async] sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Token, A => Unit]) extends State[A]
  }

  // TODO move into its proper place, flesh it out
  def benchmark() = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val ref = refOf[IO, Long](0l).unsafeRunSync()
    val promise = Promise.empty[IO, Long].unsafeRunSync()

    val count = 10000000

    def time[A](s: String)(f: IO[A]): A = {
      val start = System.currentTimeMillis()
      val a = f.unsafeRunSync()
      val took = System.currentTimeMillis() - start
      println(s"Execution of : $s took ${took.toFloat/1000} s")
      a
    }

    def op(action: IO[Unit]) : IO[Unit] = {
      def go(rem: Int): IO[Unit] = {
        if (rem == 0) IO.unit
        else action flatMap { _ => go(rem - 1) }
      }
      go(count)
    }

    println(s"Ops: $count")

    // benchmarking repeated sets for promise doesn't really matter
    // since they are typically set once. However, it would be very easy
    // to fast-path optimise it to use SyncRef after first set, achieving essentially
    // the same result.
    time("SYNC REF: setSyncPure")(op(ref.setSync(1l)))
    time("PROMISE: setSyncPure")(op(promise.setSync(1l)))
    time("SYNC REF: setAsyncPure")(op(ref.setAsync(1l)))
    time("PROMISE: setAsyncPure")(op(fork(promise.setSync(1l))))
    time("SYNC REF: get")(op(ref.get.void))
    time("PROMISE: get")(op(promise.get.void))
    time("SYNC REF: get")(op(ref.get.void))
    time("PROMISE: get")(op(promise.cancellableGet.void))
  }

}
