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

import cats.{Id, Monad}
import cats.effect.SyncIO
import cats.effect.kernel.{
  Async,
  CancelScope,
  Concurrent,
  MonadCancelThrow,
  Poll,
  Ref,
  Resource,
  Sync,
  Unique
}
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
  "Cannot find an implicit Compiler[${F}, ${G}]. This typically means you need a Concurrent[${F}] in scope"
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
          .makeCase(Scope.newRoot[F])((scope, ec) => scope.close(ec).rethrow)
          .evalMap(scope => Pull.compile(stream, scope, true, init)(foldChunk))
    }
}

private[fs2] trait CompilerLowPriority1 extends CompilerLowPriority2 {

  /** Provides a `Compiler[F, F]` instance for an effect `F` which has a `Compiler.Target`
    * instance (i.e., either a `Concurrent` or `Sync` instance).
    */
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
        .apply(stream, init)(foldChunk)
        .unsafeRunSync()
  }

  /** Type class that describes the effect used during stream compilation.
    * Instances exist for all effects which have either a `Concurrent` instance or
    * a `Sync` instance.
    */
  sealed trait Target[F[_]] extends MonadCancelThrow[F] {
    protected implicit val F: MonadCancelThrow[F]

    private[fs2] def unique: F[Unique.Token]
    private[fs2] def ref[A](a: A): F[Ref[F, A]]
    private[fs2] def interruptContext(root: Unique.Token): Option[F[InterruptContext[F]]]

    private[fs2] def compile[O, Out](
        p: Pull[F, O, Unit],
        init: Out,
        foldChunk: (Out, Chunk[O]) => Out
    ): F[Out] =
      Resource
        .makeCase(Scope.newRoot[F](this))((scope, ec) => scope.close(ec).rethrow)
        .use(scope => Pull.compile[F, O, Out](p, scope, false, init)(foldChunk))

    def pure[A](a: A): F[A] = F.pure(a)
    def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
    def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
    def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    def uncancelable[A](f: Poll[F] => F[A]): F[A] = F.uncancelable(f)
    def canceled: F[Unit] = F.canceled
    def forceR[A, B](fa: F[A])(fb: F[B]): F[B] = F.forceR(fa)(fb)
    def onCancel[A](fa: F[A], fin: F[Unit]): F[A] = F.onCancel(fa, fin)
    def rootCancelScope: CancelScope = F.rootCancelScope
  }

  private[fs2] trait TargetLowPriority {

    private final class SyncTarget[F[_]](implicit F0: Sync[F]) extends Target[F] {
      protected implicit val F: MonadCancelThrow[F] = F0
      private[fs2] def unique: F[Unique.Token] = Sync[F].unique
      private[fs2] def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
      private[fs2] def interruptContext(root: Unique.Token): Option[F[InterruptContext[F]]] = None
    }

    implicit def forSync[F[_]](implicit F: Sync[F]): Target[F] = F match {
      case async: Async[F @unchecked] => Target.forConcurrent(async)
      case _                          => new SyncTarget
    }

    private[fs2] def mkSyncTarget[F[_]: Sync]: Target[F] = new SyncTarget
  }

  object Target extends TargetLowPriority {
    private final class ConcurrentTarget[F[_]](
        protected implicit val F0: Concurrent[F]
    ) extends Target[F] {
      protected implicit val F: MonadCancelThrow[F] = F0
      private[fs2] def unique: F[Unique.Token] = Concurrent[F].unique
      private[fs2] def ref[A](a: A): F[Ref[F, A]] = F0.ref(a)
      private[fs2] def interruptContext(root: Unique.Token): Option[F[InterruptContext[F]]] = Some(
        InterruptContext(root, F.unit)
      )
    }

    implicit def forConcurrent[F[_]: Concurrent]: Target[F] =
      new ConcurrentTarget
  }
}
