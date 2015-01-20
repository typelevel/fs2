package scalaz.stream

import scalaz._

private[stream] trait ProcessInstances {

  implicit def ProcessMonadPlus[F[_]]: MonadPlus[({ type λ[α] = Process[F, α] })#λ] =
    new MonadPlus[({ type λ[α] = Process[F, α] })#λ] {
      def empty[A] = Process.halt
      def plus[A](a: Process[F, A], b: => Process[F, A]): Process[F, A] = a ++ b
      def point[A](a: => A): Process[F, A] = Process.emit(a)
      def bind[A, B](a: Process[F, A])(f: A => Process[F, B]): Process[F, B] = a flatMap f
    }

  implicit val ProcessHoist: Hoist[Process] = new ProcessHoist {}

  implicit val process1Category: Category[Process1] =
    new Category[Process1] {
      def id[A]: Process1[A, A] = process1.id
      def compose[A, B, C](f: Process1[B, C], g: Process1[A, B]): Process1[A, C] = g |> f
    }

  implicit def process1Contravariant[O]: Contravariant[({ type λ[α] = Process1[α, O] })#λ] =
    new Contravariant[({ type λ[α] = Process1[α, O] })#λ] {
      def contramap[A, B](p: Process1[A, O])(f: B => A): Process1[B, O] = p contramap f
    }
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
