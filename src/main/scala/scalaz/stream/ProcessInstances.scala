package scalaz.stream

import scalaz.{~>, Hoist, Monad, MonadPlus}

private[stream] trait ProcessInstances {

  // leaving the name for compatibility, but this really should be called something better
  implicit def ProcessMonadPlus[F[_]]: MonadPlus[({ type λ[α] = Process[F, α] })#λ] =
    new MonadPlus[({ type λ[α] = Process[F, α] })#λ] {
      def empty[A] = Process.halt
      def plus[A](a: Process[F, A], b: => Process[F, A]): Process[F, A] = a ++ b
      def point[A](a: => A): Process[F, A] = Process.emit(a)
      def bind[A, B](a: Process[F, A])(f: A => Process[F, B]): Process[F, B] = a flatMap f
    }

  implicit val ProcessHoist: Hoist[Process] = new ProcessHoist {}
}

private trait ProcessHoist extends Hoist[Process] {

  // the monad is actually unnecessary here except to match signatures
  implicit def apply[G[_]: Monad]: Monad[({ type λ[α] = Process[G, α] })#λ] =
    Process.ProcessMonadPlus

  // still unnecessary!
  def liftM[G[_]: Monad, A](a: G[A]): Process[G, A] = Process eval a

  // and more unnecessary constraints...
  def hoist[M[_]: Monad, N[_]](f: M ~> N): ({ type λ[α] = Process[M, α] })#λ ~> ({ type λ[α] = Process[N, α] })#λ = new (({ type λ[α] = Process[M, α] })#λ ~> ({ type λ[α] = Process[N, α] })#λ) {
    def apply[A](p: Process[M, A]): Process[N, A] = p translate f
  }
}
