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

import cats.effect.Async
import cats.effect.concurrent.{Deferred, Ref, Semaphore}

sealed trait Alloc[F[_]] {
  implicit def mkRef: Ref.Mk[F]
  implicit def mkDeferred: Deferred.Mk[F]
  implicit def mkSemaphore: Semaphore.Mk[F]
  implicit def mkQueue: Queue.Mk[F]
  implicit def mkSignallingRef: SignallingRef.Mk[F]
  implicit def mkBalance: Balance.Mk[F]
  implicit def mkBroadcast: Broadcast.Mk[F]
}

object Alloc {
  def apply[F[_]](implicit instance: Alloc[F]): instance.type = instance

  implicit def instance[F[_]: Async]: Alloc[F] =
    new Alloc[F] {
      implicit def mkRef: Ref.Mk[F] = Ref.MkIn.instance[F, F]
      implicit def mkDeferred: Deferred.Mk[F] = Deferred.MkIn.instance[F, F]
      implicit def mkSemaphore: Semaphore.Mk[F] = Semaphore.MkIn.instance[F, F]
      implicit def mkQueue: Queue.Mk[F] = Queue.Mk.instance[F]
      implicit def mkSignallingRef: SignallingRef.Mk[F] = SignallingRef.MkIn.instance[F, F]
      implicit def mkBalance: Balance.Mk[F] = Balance.Mk.instance[F]
      implicit def mkBroadcast: Broadcast.Mk[F] = Broadcast.Mk.instance[F]
    }
}
