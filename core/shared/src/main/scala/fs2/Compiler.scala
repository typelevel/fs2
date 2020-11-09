/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.{Id, Monad, MonadError}
import cats.effect.SyncIO
import cats.effect.kernel.{Concurrent, MonadCancelThrow, Poll, Ref, Resource, Sync}
import cats.syntax.all._

import fs2.internal._
import scala.annotation.implicitNotFound

/** Provides compilation of a `Stream[F, O]` to a `G[*]`.
  *
  * In the most common case, `F = G = IO` or another "fully featured" effect type. However, there
  * are other common instantiations like `F = Pure, G = Id`, which allows compiling a
  * `Stream[Pure, A]` in to pure values.
  *
  * For the common case where `F = G`, the `target` implicit constructor provides an instance of
  * `Compiler[F, F]` -- `target` requires a `Compiler.Target[F]` instance. The `Compiler.Target[F]` is a
  * super charged `MonadErrorThrow[F]`, providing additional capabilities needed for stream compilation.
  * `Compiler.Target[F]` instances are given for all `F[_]` which have:
  *  - `Concurrent[F]` instances
  *  - both `MonadCancelThrow[F]` and `Sync[F]` intances
  *  - only `Sync[F]` instances
  * Support for stream interruption requires compilation to an effect which has a `Concurrent` instance.
  */
@implicitNotFound(
  "Cannot find an implicit Compiler[F, G]. This typically means you need a Concurrent[F] in scope"
)
sealed trait Compiler[F[_], G[_]] {
  private[fs2] val target: Monad[G]
  private[fs2] def apply[O, B](stream: Pull[F, O, Unit], init: B)(
      fold: (B, Chunk[O]) => B
  ): G[B]
}

private[fs2] trait CompilerLowPriority2 {

  implicit def resource[F[_]: Compiler.Target]: Compiler[F, Resource[F, *]] =
    new Compiler[F, Resource[F, *]] {
      val target: Monad[Resource[F, *]] = implicitly
      def apply[O, B](
          stream: Pull[F, O, Unit],
          init: B
      )(foldChunk: (B, Chunk[O]) => B): Resource[F, B] =
        Resource
          .makeCase(CompileScope.newRoot[F])((scope, ec) => scope.close(ec).rethrow)
          .evalMap(scope => Pull.compile(stream, scope, true, init)(foldChunk))
    }
}

private[fs2] trait CompilerLowPriority1 extends CompilerLowPriority2 {
  implicit def target[F[_]](implicit F: Compiler.Target[F]): Compiler[F, F] =
    new Compiler[F, F] {
      val target: Monad[F] = implicitly
      def apply[O, B](
          stream: Pull[F, O, Unit],
          init: B
      )(foldChunk: (B, Chunk[O]) => B): F[B] = F.compile(stream, init, foldChunk)
    }
}

private[fs2] trait CompilerLowPriority0 extends CompilerLowPriority1 {
  implicit val idInstance: Compiler[Id, Id] = new Compiler[Id, Id] {
    val target: Monad[Id] = implicitly
    def apply[O, B](
        stream: Pull[Id, O, Unit],
        init: B
    )(foldChunk: (B, Chunk[O]) => B): B =
      Compiler
        .target[SyncIO]
        .apply(stream.covaryId[SyncIO], init)(foldChunk)
        .unsafeRunSync()
  }
}

private[fs2] trait CompilerLowPriority extends CompilerLowPriority0 {
  implicit val fallibleInstance: Compiler[Fallible, Either[Throwable, *]] =
    new Compiler[Fallible, Either[Throwable, *]] {
      val target: Monad[Either[Throwable, *]] = implicitly
      def apply[O, B](
          stream: Pull[Fallible, O, Unit],
          init: B
      )(foldChunk: (B, Chunk[O]) => B): Either[Throwable, B] =
        Compiler
          .target[SyncIO]
          .apply(stream.asInstanceOf[Pull[SyncIO, O, Unit]], init)(foldChunk)
          .attempt
          .unsafeRunSync()
    }
}

object Compiler extends CompilerLowPriority {
  implicit val pureInstance: Compiler[Pure, Id] = new Compiler[Pure, Id] {
    val target: Monad[Id] = implicitly
    def apply[O, B](
        stream: Pull[Pure, O, Unit],
        init: B
    )(foldChunk: (B, Chunk[O]) => B): B =
      Compiler
        .target[SyncIO]
        .apply(stream.covary[SyncIO], init)(foldChunk)
        .unsafeRunSync()
  }

  sealed trait Target[F[_]] extends MonadError[F, Throwable] {
    private[fs2] def ref[A](a: A): F[Ref[F, A]]
    private[fs2] def compile[O, Out](
        p: Pull[F, O, Unit],
        init: Out,
        foldChunk: (Out, Chunk[O]) => Out
    ): F[Out]
    private[fs2] def uncancelable[A](poll: Poll[F] => F[A]): F[A]
    private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]]
  }

  private[fs2] trait TargetLowPriority0 {
    protected abstract class MonadErrorTarget[F[_]](implicit F: MonadError[F, Throwable])
        extends Target[F] {
      def pure[A](a: A): F[A] = F.pure(a)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    }

    implicit def uncancelable[F[_]](implicit F: Sync[F]): Target[F] = new MonadErrorTarget[F]()(F) {
      private[fs2] def uncancelable[A](f: Poll[F] => F[A]): F[A] = f(idPoll)
      private val idPoll: Poll[F] = new Poll[F] { def apply[X](fx: F[X]) = fx }
      private[fs2] def compile[O, Out](
          p: Pull[F, O, Unit],
          init: Out,
          foldChunk: (Out, Chunk[O]) => Out
      ): F[Out] =
        CompileScope
          .newRoot[F](this)
          .flatMap(scope =>
            Pull
              .compile[F, O, Out](p, scope, false, init)(foldChunk)
              .redeemWith(
                t =>
                  scope
                    .close(Resource.ExitCase.Errored(t))
                    .map {
                      case Left(ts) => CompositeFailure(t, ts)
                      case Right(_) => t
                    }
                    .flatMap(raiseError),
                out =>
                  scope.close(Resource.ExitCase.Succeeded).flatMap {
                    case Left(ts) => raiseError(ts)
                    case Right(_) => pure(out)
                  }
              )
          )

      private[fs2] def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
      private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]] = None
    }
  }

  private[fs2] trait TargetLowPriority extends TargetLowPriority0 {

    implicit def forSync[F[_]](sync: Sync[F], monadCancel: MonadCancelThrow[F]): Target[F] =
      new SyncTarget[F]()(sync, monadCancel)

    protected abstract class MonadCancelTarget[F[_]](implicit F: MonadCancelThrow[F])
        extends MonadErrorTarget[F]()(F) {
      private[fs2] def uncancelable[A](f: Poll[F] => F[A]): F[A] = F.uncancelable(f)
      private[fs2] def compile[O, Out](
          p: Pull[F, O, Unit],
          init: Out,
          foldChunk: (Out, Chunk[O]) => Out
      ): F[Out] =
        Resource
          .makeCase(CompileScope.newRoot[F](this))((scope, ec) => scope.close(ec).rethrow)
          .use(scope => Pull.compile[F, O, Out](p, scope, false, init)(foldChunk))
    }

    private final class SyncTarget[F[_]: Sync: MonadCancelThrow] extends MonadCancelTarget[F] {
      private[fs2] def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
      private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]] = None
    }
  }

  object Target extends TargetLowPriority {
    implicit def forConcurrent[F[_]: Concurrent]: Target[F] =
      new ConcurrentTarget

    private final class ConcurrentTarget[F[_]](
        protected implicit val F: Concurrent[F]
    ) extends MonadCancelTarget[F]()(F) {
      private[fs2] def ref[A](a: A): F[Ref[F, A]] = F.ref(a)
      private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]] = Some(
        InterruptContext(root, F.unit)
      )
    }
  }
}
