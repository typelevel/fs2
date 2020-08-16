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
      implicit def mkQueue: Queue.Mk[F] = Queue.MkIn.instance[F, F]
      implicit def mkSignallingRef: SignallingRef.Mk[F] = SignallingRef.MkIn.instance[F, F]
      implicit def mkBalance: Balance.Mk[F] = Balance.Mk.instance[F]
      implicit def mkBroadcast: Broadcast.Mk[F] = Broadcast.Mk.instance[F]
    }
}
