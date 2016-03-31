package fs2

import Async.{Change,Future}
import fs2.util.{Free,Catchable}

@annotation.implicitNotFound("No implicit `Async[${F}]` found.\nNote that the implicit `Async[fs2.util.Task]` requires an implicit `fs2.util.Strategy` in scope.")
trait Async[F[_]] extends Catchable[F] { self =>
  type Ref[A]

  /** Create an asynchronous, concurrent mutable reference. */
  def ref[A]: F[Ref[A]]

  /** Create an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[A](a: A): F[Ref[A]] = bind(ref[A])(r => map(setPure(r)(a))(_ => r))

  /** The read-only portion of a `Ref`. */
  def read[A](r: Ref[A]): Future[F,A] = new Future[F,A] {
    def get = self.get(r)
    def cancellableGet = self.cancellableGet(r)
    def onForce = Pull.pure(())
    def force = Pull.eval(get) flatMap { a => onForce as a }
  }

  /**
   * Obtain a snapshot of the current value of the `Ref`, and a setter
   * for updating the value. The setter may noop (in which case `false`
   * is returned) if another concurrent call to `access` uses its
   * setter first. Once it has noop'd or been used once, a setter
   * never succeeds again.
   */
  def access[A](r: Ref[A]): F[(A, Either[Throwable,A] => F[Boolean])]

  /**
   * Try modifying the reference once, returning `None` if another
   * concurrent `set` or `modify` completes between the time
   * the variable is read and the time it is set.
   */
  def tryModify[A](r: Ref[A])(f: A => A): F[Option[Change[A]]] =
    bind(access(r)) { case (previous,set) =>
      val now = f(previous)
      map(set(Right(now))) { b =>
        if (b) Some(Change(previous, now))
        else None
      }
    }

  /** Repeatedly invoke `[[tryModify]](f)` until it succeeds. */
  def modify[A](r: Ref[A])(f: A => A): F[Change[A]] =
    bind(tryModify(r)(f)) {
      case None => modify(r)(f)
      case Some(change) => pure(change)
    }

  /** Obtain the value of the `Ref`, or wait until it has been `set`. */
  def get[A](r: Ref[A]): F[A] = map(access(r))(_._1)

  /**
   * Asynchronously set a reference. After the returned `F[Unit]` is bound,
   * the task is running in the background. Multiple tasks may be added to a
   * `Ref[A]`.
   *
   * Satisfies: `set(r)(t) flatMap { _ => get(r) } == t`.
   */
  def set[A](r: Ref[A])(a: F[A]): F[Unit]
  def setFree[A](r: Ref[A])(a: Free[F,A]): F[Unit]
  def setPure[A](r: Ref[A])(a: A): F[Unit] = set(r)(pure(a))
  /** Actually run the effect of setting the ref. Has side effects. */
  protected def runSet[A](q: Ref[A])(a: Either[Throwable,A]): Unit

  /**
   * Like `get`, but returns an `F[Unit]` that can be used cancel the subscription.
   */
  def cancellableGet[A](r: Ref[A]): F[(F[A], F[Unit])]

  /**
   Create an `F[A]` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version.
   */
  def async[A](register: (Either[Throwable,A] => Unit) => F[Unit]): F[A] =
    bind(ref[A]) { ref =>
    bind(register { e => runSet(ref)(e) }) { _ => get(ref) }}

  def parallelTraverse[A,B](s: Seq[A])(f: A => F[B]): F[Vector[B]] =
    bind(traverse(s)(f andThen start)) { tasks => traverse(tasks)(identity) }

  /**
   * Begin asynchronous evaluation of `f` when the returned `F[F[A]]` is
   * bound. The inner `F[A]` will block until the result is available.
   */
  def start[A](f: F[A]): F[F[A]] =
    bind(ref[A]) { ref =>
    bind(set(ref)(f)) { _ => pure(get(ref)) }}
}

object Async {

  trait Future[F[_],A] { self =>
    private[fs2] def get: F[A]
    private[fs2] def cancellableGet: F[(F[A], F[Unit])]
    private[fs2] def onForce: Pull[F,Nothing,Unit]
    private[fs2] def appendOnForce(p: Pull[F,Nothing,Unit]): Future[F,A] = new Future[F,A] {
      def get = self.get
      def cancellableGet = self.cancellableGet
      def onForce = self.onForce >> p
      def force = self.force flatMap { a => p as a }
    }
    def force: Pull[F,Nothing,A]
    def stream: Stream[F,A] = force.flatMap(a => Pull.output1(a)).runAsStep
    def map[B](f: A => B)(implicit F: Async[F]): Future[F,B] = new Future[F,B] {
      def get = F.map(self.get)(f)
      def cancellableGet = F.map(self.cancellableGet) { case (a,cancelA) => (F.map(a)(f), cancelA) }
      def force = Pull.eval(get) flatMap { r => onForce as r }
      def onForce = self.onForce
    }

    def race[B](b: Future[F,B])(implicit F: Async[F]): Future[F,Either[A,B]] = new Future[F, Either[A,B]] {
      def get = F.bind(cancellableGet)(_._1)
      def cancellableGet =
        F.bind(F.ref[Either[A,B]]) { ref =>
        F.bind(self.cancellableGet) { case (a, cancelA) =>
        F.bind(b.cancellableGet) { case (b, cancelB) =>
        F.bind(F.set(ref)(F.map(a)(Left(_)))) { _ =>
        F.bind(F.set(ref)(F.map(b)(Right(_)))) { _ =>
        F.pure {
         (F.bind(F.get(ref)) {
           case Left(a) => F.map(cancelB)(_ => Left(a))
           case Right(b) => F.map(cancelA)(_ => Right(b)) },
          F.bind(cancelA)(_ => cancelB))
        }}}}}}
      def force = Pull.eval(get) flatMap {
        case Left(ar) => self.onForce as (Left(ar))
        case Right(br) => b.onForce as (Right(br))
      }
      def onForce = force map (_ => ())
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
      def get = F.pure(a)
      def cancellableGet = F.pure((get, F.pure(())))
      def onForce = Pull.pure(())
      def force = Pull.pure(a)
    }

    def race[F[_]:Async,A](es: Vector[Future[F,A]])
      : Future[F,Focus[A,Future[F,A]]]
      = indexedRace(es) map { case (a, i) => Focus(a, i, es) }

    private[fs2] def indexedRace[F[_],A](es: Vector[Future[F,A]])(implicit F: Async[F])
      : Future[F,(A,Int)]
      = new Future[F,(A,Int)] {
        def cancellableGet =
          F.bind(F.ref[(A,Int)]) { ref =>
            val cancels: F[Vector[(F[Unit],Int)]] = F.traverse(es zip (0 until es.size)) { case (a,i) =>
              F.bind(a.cancellableGet) { case (a, cancelA) =>
              F.map(F.set(ref)(F.map(a)((_,i))))(_ => (cancelA,i)) }
            }
          F.bind(cancels) { cancels =>
          F.pure {
            val get = F.bind(F.get(ref)) { case (a,i) =>
              F.map(F.traverse(cancels.collect { case (a,j) if j != i => a })(identity))(_ => (a,i)) }
            val cancel = F.map(F.traverse(cancels)(_._1))(_ => ())
            (get, cancel)
          }}}
        def get = F.bind(cancellableGet)(_._1)
        def force = Pull.eval(get) flatMap { case (a,i) =>
          es(i).onForce >> Pull.pure(a -> i)
        }
        def onForce = force map (_ => ())
      }
  }
  /**
   * The result of a `Ref` modification. `previous` contains value before modification
   * (the value passed to modify function, `f` in the call to `modify(f)`. And `now`
   * is the new value computed by `f`.
   */
  case class Change[+A](previous: A, now: A)
}
