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

import cats.effect.{ConcurrentThrow, Resource, Sync}
import cats.effect.concurrent.Ref

sealed trait CompilationTarget[F[_]] extends Resource.Bracket[F] {
  def ref[A](a: A): F[Ref[F, A]]
}

private[fs2] trait CompilationTargetLowPriority {
  implicit def forSync[F[_]: Sync]: CompilationTarget[F] = new SyncCompilationTarget

  private final class SyncCompilationTarget[F[_]](protected implicit val F: Sync[F])
      extends CompilationTarget[F]
      with Resource.Bracket.SyncBracket[F] {
    def ref[A](a: A): F[Ref[F, A]] = Ref[F].of(a)
  }
}

object CompilationTarget extends CompilationTargetLowPriority {
  implicit def forConcurrent[F[_]: ConcurrentThrow]: CompilationTarget[F] =
    new ConcurrentCompilationTarget

  private final class ConcurrentCompilationTarget[F[_]](
      protected implicit val F: ConcurrentThrow[F]
  ) extends CompilationTarget[F]
      with Resource.Bracket.SpawnBracket[F] {
    def ref[A](a: A): F[Ref[F, A]] = F.ref(a)
  }
}
