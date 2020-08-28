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

package fs2.concurrent

import cats.effect.{Concurrent, ConcurrentThrow, Poll}
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.syntax.all._

// TODO delete once cats-effect has the equivalent
object next {
  trait Alloc[F[_]] extends Concurrent[F, Throwable] {
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  }
  object Alloc {
    def apply[F[_]](implicit F: Alloc[F]): F.type = F

    implicit def instance[F[_]](implicit F: ConcurrentThrow[F], refMk: Ref.Mk[F], defMk: Deferred.Mk[F]): Alloc[F] = new Alloc[F] {
      type E = Throwable

      def deferred[A] = Deferred[F, A]
      def ref[A](a: A) = Ref[F].of(a)
      def pure[A](x: A) = F.pure(x)
      def flatMap[A, B](fa: F[A])(f: A => F[B]) = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]) = F.tailRecM(a)(f)
      def handleErrorWith [A](fa: F[A])(f: E => F[A]) = F.handleErrorWith(fa)(f)
      def raiseError [A](e: E) = F.raiseError(e)
      def canceled: F[Unit] = F.canceled
      def cede: F[Unit] = F.cede
      def forceR[A, B](fa: F[A])(fb: F[B]) = F.forceR(fa)(fb)
      def never[A]: F[A] = F.never
      def onCancel[A](fa: F[A], fin: F[Unit]) = F.onCancel(fa, fin)
      def racePair[A, B](fa: F[A], fb: F[B]) = F.racePair(fa, fb)
      def start[A](fa: F[A]) = F.start(fa)
      def uncancelable[A](body: Poll[F] => F[A]): F[A] = F.uncancelable(body)
    }
  }
}

sealed trait Alloc[F[_]] {
  implicit def mkRef: Ref.Mk[F]
  implicit def mkDeferred: Deferred.Mk[F]
  implicit def mkSemaphore: Semaphore.Mk[F]
}

object Alloc {
  def apply[F[_]](implicit instance: Alloc[F]): instance.type = instance

  import cats.effect.Async
  implicit def instance[F[_]: Async]: Alloc[F] =
    new Alloc[F] {
      implicit def mkRef: Ref.Mk[F] = Ref.MkIn.instance[F, F]
      implicit def mkDeferred: Deferred.Mk[F] = Deferred.MkIn.instance[F, F]
      implicit def mkSemaphore: Semaphore.Mk[F] = Semaphore.MkIn.instance[F, F]
    }
}
