package fs2

import Async.Ref
import fs2.util.{Functor,Effect}
import fs2.util.syntax._

@annotation.implicitNotFound("No implicit `Async[${F}]` found.\nNote that the implicit `Async[fs2.Task]` requires an implicit `fs2.Strategy` in scope.")
trait Async[F[_]] extends Effect[F] { self =>

  /** Create an asynchronous, concurrent mutable reference. */
  def ref[A]: F[Ref[F,A]]

  /** Create an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[A](a: A): F[Ref[F,A]] = flatMap(ref[A])(r => map(r.setPure(a))(_ => r))

  /**
   Create an `F[A]` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version.
   */
  def async[A](register: (Either[Throwable,A] => Unit) => F[Unit]): F[A] =
    flatMap(ref[A]) { ref =>
    flatMap(register { e => unsafeRunAsync(ref.set(e.fold(fail, pure)))(_ => ()) }) { _ => ref.get }}

  def parallelTraverse[A,B](s: Seq[A])(f: A => F[B]): F[Vector[B]] =
    flatMap(traverse(s)(f andThen start)) { tasks => traverse(tasks)(identity) }

  /**
   * Begin asynchronous evaluation of `f` when the returned `F[F[A]]` is
   * bound. The inner `F[A]` will block until the result is available.
   */
  def start[A](f: F[A]): F[F[A]] =
    flatMap(ref[A]) { ref =>
    flatMap(ref.set(f)) { _ => pure(ref.get) }}
}

object Async {

  /** Create an asynchronous, concurrent mutable reference. */
  def ref[F[_],A](implicit F:Async[F]): F[Async.Ref[F,A]] = F.ref

  /** Create an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[F[_],A](a: A)(implicit F:Async[F]): F[Async.Ref[F,A]] = F.refOf(a)

  /** An asynchronous, concurrent mutable reference. */
  trait Ref[F[_],A] { self =>
    implicit protected val F: Async[F]

    /**
     * Obtain a snapshot of the current value of the `Ref`, and a setter
     * for updating the value. The setter may noop (in which case `false`
     * is returned) if another concurrent call to `access` uses its
     * setter first. Once it has noop'd or been used once, a setter
     * never succeeds again.
     */
    def access: F[(A, Either[Throwable,A] => F[Boolean])]

    /** Obtain the value of the `Ref`, or wait until it has been `set`. */
    def get: F[A] = access.map(_._1)

    /** Like `get`, but returns an `F[Unit]` that can be used cancel the subscription. */
    def cancellableGet: F[(F[A], F[Unit])]

    /** The read-only portion of a `Ref`. */
    def read: Future[F,A] = new Future[F,A] {
      def get = self.get.map((_,Scope.pure(())))
      def cancellableGet = self.cancellableGet.map { case (f,cancel) =>
        (f.map((_,Scope.pure(()))), cancel)
      }
    }

    /**
     * Try modifying the reference once, returning `None` if another
     * concurrent `set` or `modify` completes between the time
     * the variable is read and the time it is set.
     */
    def tryModify(f: A => A): F[Option[Change[A]]] =
      access.flatMap { case (previous,set) =>
        val now = f(previous)
        set(Right(now)).map { b =>
          if (b) Some(Change(previous, now))
          else None
        }
      }

    /** Like `tryModify` but allows to return `B` along with change. **/
    def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]] =
      access.flatMap { case (previous,set) =>
        val (now,b0) = f(previous)
        set(Right(now)).map { b =>
          if (b) Some(Change(previous, now) -> b0)
          else None
        }
    }

    /** Repeatedly invoke `[[tryModify]](f)` until it succeeds. */
    def modify(f: A => A): F[Change[A]] =
      tryModify(f).flatMap {
        case None => modify(f)
        case Some(change) => F.pure(change)
      }

    /** Like modify, but allows to extra `b` in single step. **/
    def modify2[B](f: A => (A,B)): F[(Change[A], B)] =
      tryModify2(f).flatMap {
        case None => modify2(f)
        case Some(changeAndB) => F.pure(changeAndB)
      }

    /**
     * Asynchronously set a reference. After the returned `F[Unit]` is bound,
     * the task is running in the background. Multiple tasks may be added to a
     * `Ref[A]`.
     *
     * Satisfies: `r.set(t) flatMap { _ => r.get } == t`.
     */
    def set(a: F[A]): F[Unit]

    /**
     * Asynchronously set a reference to a pure value.
     *
     * Satisfies: `r.setPure(a) flatMap { _ => r.get(a) } == pure(a)`.
     */
    def setPure(a: A): F[Unit] = set(F.pure(a))
  }

  trait Future[F[_],A] { self =>
    private[fs2] def get: F[(A, Scope[F,Unit])]
    private[fs2] def cancellableGet: F[(F[(A, Scope[F,Unit])], F[Unit])]
    private[fs2] def appendOnForce(p: Scope[F,Unit])(implicit F: Functor[F]): Future[F,A] = new Future[F,A] {
      def get = self.get.map { case (a,s0) => (a, s0 flatMap { _ => p }) }
      def cancellableGet =
        self.cancellableGet.map { case (f,cancel) =>
          (f.map { case (a,s0) => (a, s0 flatMap { _ => p }) }, cancel)
        }
    }
    def force: Pull[F,Nothing,A] = Pull.eval(get) flatMap { case (a,onForce) => Pull.evalScope(onForce as a) }
    def stream: Stream[F,A] = Stream.eval(get) flatMap { case (a,onForce) => Stream.evalScope(onForce as a) }
    def map[B](f: A => B)(implicit F: Async[F]): Future[F,B] = new Future[F,B] {
      def get = self.get.map { case (a,onForce) => (f(a), onForce) }
      def cancellableGet = self.cancellableGet.map { case (a,cancelA) =>
        (a.map { case (a,onForce) => (f(a),onForce) }, cancelA)
      }
    }
    def race[B](b: Future[F,B])(implicit F: Async[F]): Future[F,Either[A,B]] = new Future[F, Either[A,B]] {
      def get = cancellableGet.flatMap(_._1)
      def cancellableGet = for {
        ref <- F.ref[Either[(A,Scope[F,Unit]),(B,Scope[F,Unit])]]
        t0 <- self.cancellableGet
        (a, cancelA) = t0
        t1 <- b.cancellableGet
        (b, cancelB) = t1
        _ <- ref.set(a.map(Left(_)))
        _ <- ref.set(b.map(Right(_)))
      } yield {
        (ref.get.flatMap {
          case Left((a,onForce)) => cancelB.as((Left(a),onForce))
          case Right((b,onForce)) => cancelA.as((Right(b),onForce))
        }, cancelA >> cancelB)
      }
    }

    def raceSame(b: Future[F,A])(implicit F: Async[F]): Future[F, RaceResult[A,Future[F,A]]] =
      self.race(b).map {
        case Left(a) => RaceResult(a, b)
        case Right(a) => RaceResult(a, self)
      }
  }

  def race[F[_]:Async,A](es: Vector[Future[F,A]])
    : Future[F,Focus[A,Future[F,A]]]
    = Future.race(es)

  case class Focus[A,B](get: A, index: Int, v: Vector[B]) {
    def replace(b: B): Vector[B] = v.patch(index, List(b), 1)
    def delete: Vector[B] = v.patch(index, List(), 1)
  }

  case class RaceResult[+A,+B](winner: A, loser: B)

  object Future {

    def pure[F[_],A](a: A)(implicit F: Async[F]): Future[F,A] = new Future[F,A] {
      def get = F.pure(a -> Scope.pure(()))
      def cancellableGet = F.pure((get, F.pure(())))
    }

    def race[F[_]:Async,A](es: Vector[Future[F,A]])
      : Future[F,Focus[A,Future[F,A]]]
      = indexedRace(es) map { case (a, i) => Focus(a, i, es) }

    private[fs2] def indexedRace[F[_],A](es: Vector[Future[F,A]])(implicit F: Async[F])
      : Future[F,(A,Int)]
      = new Future[F,(A,Int)] {
        def cancellableGet =
          F.ref[((A,Scope[F,Unit]),Int)].flatMap { ref =>
            val cancels: F[Vector[(F[Unit],Int)]] = (es zip (0 until es.size)).traverse { case (a,i) =>
              a.cancellableGet.flatMap { case (a, cancelA) =>
                ref.set(a.map((_,i))).as((cancelA,i))
              }
            }
            cancels.flatMap { cancels =>
              F.pure {
                val get = ref.get.flatMap { case (a,i) =>
                  F.sequence(cancels.collect { case (a,j) if j != i => a }).map(_ => (a,i))
                }
                val cancel = cancels.traverse(_._1).as(())
                (get.map { case ((a,onForce),i) => ((a,i),onForce) }, cancel)
              }
            }
          }
        def get = cancellableGet.flatMap(_._1)
      }
  }
  /**
   * The result of a `Ref` modification. `previous` contains value before modification
   * (the value passed to modify function, `f` in the call to `modify(f)`. And `now`
   * is the new value computed by `f`.
   */
  case class Change[+A](previous: A, now: A) {
    def modified: Boolean = previous != now
    def map[B](f: A => B): Change[B] = Change(f(previous), f(now))
  }
}
