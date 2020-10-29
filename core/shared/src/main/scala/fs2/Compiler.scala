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
import cats.effect.kernel.{Concurrent, MonadCancelThrow, Poll, Ref, Resource, Sync}
import cats.syntax.all._

import fs2.internal._
import scala.annotation.implicitNotFound

/** Type class which describes compilation of a `Stream[F, O]` to a `G[*]`. */
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
  implicit def target[F[_]: Compiler.Target]: Compiler[F, F] =
    new Compiler[F, F] {
      val target: Monad[F] = implicitly
      def apply[O, B](
          stream: Pull[F, O, Unit],
          init: B
      )(foldChunk: (B, Chunk[O]) => B): F[B] =
        Resource
          .Bracket[F]
          .bracketCase(CompileScope.newRoot[F])(scope =>
            Pull.compile[F, O, B](stream, scope, false, init)(foldChunk)
          )((scope, ec) => scope.close(ec).rethrow)
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

  sealed trait Target[F[_]] extends MonadCancelThrow[F] {
    def ref[A](a: A): F[Ref[F, A]]
    private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]]
  }

  private[fs2] trait TargetLowPriority {
    // TODO Delete this once SyncIO has a MonadCancelThrow instance
    implicit def forSyncIO: Target[SyncIO] = new Target[SyncIO] {
      def pure[A](a: A): SyncIO[A] = SyncIO.pure(a)
      def handleErrorWith[A](fa: SyncIO[A])(f: Throwable => SyncIO[A]): SyncIO[A] =
        fa.handleErrorWith(f)
      def raiseError[A](e: Throwable): SyncIO[A] = SyncIO.raiseError(e)
      def flatMap[A, B](fa: SyncIO[A])(f: A => SyncIO[B]): SyncIO[B] = fa.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => SyncIO[Either[A, B]]): SyncIO[B] =
        Monad[SyncIO].tailRecM(a)(f)
      def canceled: SyncIO[Unit] = SyncIO.unit
      def forceR[A, B](fa: SyncIO[A])(fb: SyncIO[B]): SyncIO[B] = fa *> fb
      def onCancel[A](fa: SyncIO[A], fin: SyncIO[Unit]): SyncIO[A] = fa
      def uncancelable[A](f: Poll[SyncIO] => SyncIO[A]): SyncIO[A] = f(idPoll)
      private val idPoll: Poll[SyncIO] = new Poll[SyncIO] { def apply[X](fx: SyncIO[X]) = fx }
      def ref[A](a: A): SyncIO[Ref[SyncIO, A]] = Ref[SyncIO].of(a)
      private[fs2] def interruptContext(root: Token): Option[SyncIO[InterruptContext[SyncIO]]] =
        None
    }

    implicit def forSync[F[_]: Sync: MonadCancelThrow]: Target[F] = new SyncTarget

    protected abstract class MonadCancelTarget[F[_]](implicit F: MonadCancelThrow[F])
        extends Target[F] {
      def pure[A](a: A): F[A] = F.pure(a)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
      def canceled: F[Unit] = F.canceled
      def forceR[A, B](fa: F[A])(fb: F[B]): F[B] = F.forceR(fa)(fb)
      def onCancel[A](fa: F[A], fin: F[Unit]): F[A] = F.onCancel(fa, fin)
      def uncancelable[A](f: Poll[F] => F[A]): F[A] = F.uncancelable(f)
    }

    private final class SyncTarget[F[_]: Sync: MonadCancelThrow] extends MonadCancelTarget[F] {
      def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
      private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]] = None
    }
  }

  object Target extends TargetLowPriority {
    implicit def forConcurrent[F[_]: Concurrent]: Target[F] =
      new ConcurrentTarget

    private final class ConcurrentTarget[F[_]](
        protected implicit val F: Concurrent[F]
    ) extends MonadCancelTarget[F] {
      def ref[A](a: A): F[Ref[F, A]] = F.ref(a)
      private[fs2] def interruptContext(root: Token): Option[F[InterruptContext[F]]] = Some(
        InterruptContext(root, F.unit)
      )
    }
  }
}
