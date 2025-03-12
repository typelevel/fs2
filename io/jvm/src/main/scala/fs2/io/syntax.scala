package fs2.io

import cats.effect.kernel.Async
import fs2.io

package object syntax {
  implicit final class AsyncOps[F[_]: Async, A](private val self: F[A]) {
    def evalOnVirtualThreadIfAvailable(): F[A] = io.evalOnVirtualThreadIfAvailable(self)
  }
}
