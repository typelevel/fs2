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

import cats.{Applicative, Id, Monad}
import cats.effect._
import cats.effect.concurrent.Ref
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

  sealed trait Target[F[_]] extends Resource.Bracket[F] {
    def ref[A](a: A): F[Ref[F, A]]
  }

  private[fs2] trait TargetLowPriority {
    implicit def forSync[F[_]: Sync]: Target[F] = new SyncTarget

    private final class SyncTarget[F[_]](protected implicit val F: Sync[F])
        extends Target[F]
        with Resource.Bracket.SyncBracket[F] {
      def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
    }
  }

  object Target extends TargetLowPriority {
    implicit def forConcurrent[F[_]: Concurrent]: Target[F] =
      new ConcurrentTarget

    private final class ConcurrentTarget[F[_]](
        protected implicit val F: Concurrent[F]
    ) extends Target[F]
        with Resource.Bracket.MonadCancelBracket[F] {
      def ref[A](a: A): F[Ref[F, A]] = F.ref(a)
    }
  }
}
