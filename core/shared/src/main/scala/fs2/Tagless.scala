package fs2.tagless

import fs2.{Chunk, Fallible, INothing, Pure}

import cats._
import cats.implicits._
import cats.effect._

import scala.collection.generic.CanBuildFrom

sealed trait Pull[+F[_], +O, +R] {
  def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(implicit F: Sync[F2]): F2[(S, R2)]
  def step: Pull[F, INothing, Either[R, (Chunk[O], Pull[F, O, R])]]
  def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R]

  def flatMap[F2[x] >: F[x], R2, O2 >: O](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Pull.FlatMap[F2, O2, R, R2](this, f)

  final def >>[F2[x] >: F[x], O2 >: O, R2 >: R](that: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap(_ => that)

  def map[R2](f: R => R2): Pull[F, O, R2] = flatMap(r => Pull.pure(f(r)))
}

object Pull {

  val done: Pull[Pure, INothing, Unit] = pure(())

  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] = new Result[R](r)
  
  private final class Result[R](r: R) extends Pull[Pure, INothing, R] {
    def compile[F2[x] >: Pure[x], R2 >: R, S](initial: S)(f: (S, Chunk[INothing]) => S)(implicit F: Sync[F2]): F2[(S, R2)] =
      (initial, r: R2).pure[F2]
    def step: Pull[Pure, INothing, Either[R, (Chunk[INothing], Pull[Pure, INothing, R])]] =
      pure(Either.left[R, (Chunk[INothing], Pull[Pure, INothing, R])](r))
    def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] = this
    override def map[R2](f: R => R2): Pull[Pure, INothing, R2] = pure(f(r))
  }

  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = new Output(Chunk.singleton(o))

  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) done else new Output(os)

  private final class Output[O](os: Chunk[O]) extends Pull[Pure, O, Unit] {
    def compile[F2[x] >: Pure[x], R2 >: Unit, S](initial: S)(f: (S, Chunk[O]) => S)(implicit F: Sync[F2]): F2[(S, R2)] =
      (f(initial, os), (): R2).pure[F2]
    def step: Pull[Pure, INothing, Either[Unit, (Chunk[O], Pull[Pure, O, Unit])]] =
      pure(Either.right((os -> Pull.done)))
    def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, O, Unit] = this
  }

  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = new Eval(fr)

  private final class Eval[F[_], R](fr: F[R]) extends Pull[F, INothing, R] {
    def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[INothing]) => S)(implicit F: Sync[F2]): F2[(S, R2)] =
      (fr: F2[R]).map(r => (initial, r: R2))
    def step: Pull[F, INothing, Either[R, (Chunk[INothing], Pull[F, INothing, R])]] =
      eval(fr).flatMap(r => pure(Either.left(r)))
    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] =
      eval(f(fr))
  }

  private final class FlatMap[F[_], O, R0, R](source: Pull[F, O, R0], g: R0 => Pull[F, O, R]) extends Pull[F, O, R] {
    def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(implicit F: Sync[F2]): F2[(S, R2)] =
      source.compile[F2, R0, S](initial)(f).flatMap { case (s, r) => g(r).compile[F2, R2, S](s)(f) }

    def step: Pull[F, INothing, Either[R, (Chunk[O], Pull[F, O, R])]] =
      source.step.flatMap {
        case Right((hd, tl)) => Pull.pure(Right((hd, tl.flatMap(g))))
        case Left(r) => g(r).step
      }

    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      source.translate(f).flatMap(r => g(r).translate(f))
  }

  implicit def monadInstance[F[_], O]: Monad[Pull[F, O, ?]] =
    new Monad[Pull[F, O, ?]] {
      def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
      def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]) = p.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Pull.pure(b)
        }
    }
}

final class Stream[+F[_], +O] private (private val asPull: Pull[F, O, Unit]) extends AnyVal {

  def compile[F2[x] >: F[x], G[_], O2 >: O](
      implicit compiler: Stream.Compiler[F2, G]): Stream.CompileOps[F2, G, O2] =
    new Stream.CompileOps[F2, G, O2](asPull)

  def covary[F2[x] >: F[x]]: Stream[F2, O] = this

  def translate[F2[x] >: F[x], G[_]](u: F2 ~> G): Stream[G, O] =
    Stream.fromPull(asPull.translate(u))
}

object Stream {
  private[fs2] def fromPull[F[_], O](p: Pull[F, O, Unit]): Stream[F, O] = new Stream(p)

  def apply[O](os: O*): Stream[Pure, O] = emits(os)

  def chunk[O](c: Chunk[O]): Stream[Pure, O] = fromPull(Pull.output(c))

  def emit[O](o: O): Stream[Pure, O] = fromPull(Pull.output1(o))

  def emits[O](os: Seq[O]): Stream[Pure, O] =
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromPull(Pull.output(Chunk.seq(os)))

  val empty: Stream[Pure, Nothing] = fromPull(Pull.done)

  def eval[F[_], O](fo: F[O]): Stream[F, O] =
    fromPull(Pull.eval(fo).flatMap(o => Pull.output1(o)))

  
  /** Provides syntax for streams with effect type `cats.Id`. */
  implicit def IdOps[O](s: Stream[Id, O]): IdOps[O] = new IdOps(s.asPull)

  /** Provides syntax for pure pipes based on `cats.Id`. */
  final class IdOps[O] private[Stream] (private val asPull: Pull[Id, O, Unit]) extends AnyVal {
    private def self: Stream[Id, O] = Stream.fromPull(asPull)

    private def idToApplicative[F[_]: Applicative]: Id ~> F =
      new (Id ~> F) { def apply[A](a: Id[A]) = a.pure[F] }

    def covaryId[F[_]: Applicative]: Stream[F, O] = self.translate(idToApplicative[F])
  }

  /** Provides syntax for streams with effect type `Fallible`. */
  implicit def FallibleOps[O](s: Stream[Fallible, O]): FallibleOps[O] =
    new FallibleOps(s.asPull)

  /** Provides syntax for fallible streams. */
  final class FallibleOps[O] private[Stream] (
      private val asPull: Pull[Fallible, O, Unit])
      extends AnyVal {
    private def self: Stream[Fallible, O] = Stream.fromPull(asPull)

    /** Lifts this stream to the specified effect type. */
    def lift[F[_]](implicit F: ApplicativeError[F, Throwable]): Stream[F, O] = {
      val _ = F
      self.asInstanceOf[Stream[F, O]]
    }

    /** Runs this fallible stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on fallible streams. */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): Either[Throwable, C[O]] =
      lift[IO].compile.to[C].attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a chunk. Note: this method is only available on fallible streams. */
    def toChunk: Either[Throwable, Chunk[O]] = lift[IO].compile.toChunk.attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a list. Note: this method is only available on fallible streams. */
    def toList: Either[Throwable, List[O]] = lift[IO].compile.toList.attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a vector. Note: this method is only available on fallible streams. */
    def toVector: Either[Throwable, Vector[O]] =
      lift[IO].compile.toVector.attempt.unsafeRunSync
  }

  /** Type class which describes compilation of a `Stream[F, O]` to a `G[?]`. */
  sealed trait Compiler[F[_], G[_]] {
    private[Stream] def apply[O, B, C](s: Stream[F, O], init: Eval[B])(fold: (B, Chunk[O]) => B,
                                                                       finalize: B => C): G[C]
  }

  object Compiler {
    implicit def syncInstance[F[_]](implicit F: Sync[F]): Compiler[F, F] = new Compiler[F, F] {
      def apply[O, B, C](s: Stream[F, O], init: Eval[B])(foldChunk: (B, Chunk[O]) => B,
                                                         finalize: B => C): F[C] =
        F.delay(init.value).flatMap(i => s.asPull.compile(i)(foldChunk)).map { case (b, _) => finalize(b) }
    }

    implicit val pureInstance: Compiler[Pure, Id] = new Compiler[Pure, Id] {
      def apply[O, B, C](s: Stream[Pure, O], init: Eval[B])(foldChunk: (B, Chunk[O]) => B,
                                                            finalize: B => C): C =
        finalize(s.covary[IO].asPull.compile(init.value)(foldChunk).unsafeRunSync._1)
    }

    implicit val idInstance: Compiler[Id, Id] = new Compiler[Id, Id] {
      def apply[O, B, C](s: Stream[Id, O], init: Eval[B])(foldChunk: (B, Chunk[O]) => B,
                                                          finalize: B => C): C =
        finalize(s.covaryId[IO].asPull.compile(init.value)(foldChunk).unsafeRunSync._1)
    }

    implicit val fallibleInstance: Compiler[Fallible, Either[Throwable, ?]] =
      new Compiler[Fallible, Either[Throwable, ?]] {
        def apply[O, B, C](s: Stream[Fallible, O], init: Eval[B])(
            foldChunk: (B, Chunk[O]) => B,
            finalize: B => C): Either[Throwable, C] =
          s.lift[IO].asPull.compile(init.value)(foldChunk).attempt.unsafeRunSync.map { case (b, _) => finalize(b) }
      }
  }

  /** Projection of a `Stream` providing various ways to compile a `Stream[F,O]` to an `F[...]`. */
  final class CompileOps[F[_], G[_], O] private[Stream] (private val self: Pull[F, O, Unit])(
      implicit compiler: Compiler[F, G]) {

    /**
      * Compiles this stream in to a value of the target effect type `F` and
      * discards any output values of the stream.
      *
      * To access the output values of the stream, use one of the other compilation methods --
      * e.g., [[fold]], [[toVector]], etc.
      */
    def drain: G[Unit] = foldChunks(())((_, _) => ())

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output values together, starting with the provided `init` and combining the
      * current value with each output value.
      */
    def fold[B](init: B)(f: (B, O) => B): G[B] =
      foldChunks(init)((acc, c) => c.foldLeft(acc)(f))

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output chunks together, starting with the provided `init` and combining the
      * current value with each output chunk.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def foldChunks[B](init: B)(f: (B, Chunk[O]) => B): G[B] =
      compiler(fromPull(self), Eval.now(init))(f, identity)

    /**
      * Like [[fold]] but uses the implicitly available `Monoid[O]` to combine elements.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldMonoid.unsafeRunSync
      * res0: Int = 15
      * }}}
      */
    def foldMonoid(implicit O: Monoid[O]): G[O] =
      fold(O.empty)(O.combine)

    /**
      * Like [[fold]] but uses the implicitly available `Semigroup[O]` to combine elements.
      * If the stream emits no elements, `None` is returned.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldSemigroup.unsafeRunSync
      * res0: Option[Int] = Some(15)
      * scala> Stream.empty.covaryAll[IO, Int].compile.foldSemigroup.unsafeRunSync
      * res1: Option[Int] = None
      * }}}
      */
    def foldSemigroup(implicit O: Semigroup[O]): G[Option[O]] =
      fold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * returning `None` if the stream emitted no values and returning the
      * last value emitted wrapped in `Some` if values were emitted.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.last.unsafeRunSync
      * res0: Option[Int] = Some(4)
      * }}}
      */
    def last: G[Option[O]] =
      foldChunks(Option.empty[O])((acc, c) => c.last.orElse(acc))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * raising a `NoSuchElementException` if the stream emitted no values
      * and returning the last value emitted otherwise.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.lastOrError.unsafeRunSync
      * res0: Int = 4
      * scala> Stream.empty.covaryAll[IO, Int].compile.lastOrError.attempt.unsafeRunSync
      * res1: Either[Throwable, Int] = Left(java.util.NoSuchElementException)
      * }}}
      */
    def lastOrError(implicit G: MonadError[G, Throwable]): G[O] =
      last.flatMap(_.fold(G.raiseError(new NoSuchElementException): G[O])(G.pure))

    /**
      * Compiles this stream into a value of the target effect type `F` by logging
      * the output values to a `C`, given a `CanBuildFrom`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.to[List].unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): G[C[O]] =
      compiler(Stream.fromPull(self), Eval.always(cbf()))(_ ++= _.iterator, _.result)

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Chunk`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toChunk.unsafeRunSync
      * res0: Chunk[Int] = Chunk(0, 1, 2, 3, 4)
      * }}}
      */
    def toChunk: G[Chunk[O]] =
      compiler(Stream.fromPull(self), Eval.always(List.newBuilder[Chunk[O]]))(_ += _,
                                                             bldr => Chunk.concat(bldr.result))

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `List`. Equivalent to `to[List]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toList.unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def toList: G[List[O]] =
      to[List]

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Vector`. Equivalent to `to[Vector]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
      * }}}
      */
    def toVector: G[Vector[O]] =
      to[Vector]
  }
}