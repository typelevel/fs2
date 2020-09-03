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

package fs2.internal

import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref
import cats.effect.kernel.ConcurrentThrow

import Resource.ExitCase

sealed trait CompilationTarget[F[_]] extends Resource.Bracket[F] {
  def ref[A](a: A): F[Ref[F, A]]
}

private[fs2] trait CompilationTargetLowPriority {
  implicit def forSync[F[_]](implicit F: Sync[F]): CompilationTarget[F] =
    new CompilationTarget[F] {
      def pure[A](a: A) = F.pure(a)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def bracketCase[A, B](
          acquire: F[A]
      )(use: A => F[B])(release: (A, Resource.ExitCase) => F[Unit]): F[B] =
        flatMap(acquire) { a =>
          val handled = onError(use(a)) {
            case e => void(attempt(release(a, ExitCase.Errored(e))))
          }
          flatMap(handled)(b => as(attempt(release(a, ExitCase.Completed)), b))
        }
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
      def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
    }
}

object CompilationTarget extends CompilationTargetLowPriority {
  implicit def forConcurrent[F[_]](implicit F: ConcurrentThrow[F]): CompilationTarget[F] =
    new CompilationTarget[F] {
      def pure[A](a: A) = F.pure(a)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def bracketCase[A, B](
          acquire: F[A]
      )(use: A => F[B])(release: (A, Resource.ExitCase) => F[Unit]): F[B] =
        F.uncancelable { poll =>
          flatMap(acquire) { a =>
            val finalized = F.onCancel(poll(use(a)), release(a, ExitCase.Canceled))
            val handled = onError(finalized) {
              case e => void(attempt(release(a, ExitCase.Errored(e))))
            }
            flatMap(handled)(b => as(attempt(release(a, ExitCase.Completed)), b))
          }
        }
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
      def ref[A](a: A): F[Ref[F, A]] = F.ref(a)
    }
}
