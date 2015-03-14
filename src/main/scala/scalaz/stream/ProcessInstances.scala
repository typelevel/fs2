package scalaz.stream

import scalaz._

private[stream] trait ProcessInstances {

  implicit val processHoist: Hoist[Process] = new ProcessHoist {}

  implicit def processMonadPlus[F[_]]: MonadPlus[({ type λ[α] = Process[F, α] })#λ] =
    new MonadPlus[({ type λ[α] = Process[F, α] })#λ] {
      def empty[A] = Process.halt
      def plus[A](a: Process[F, A], b: => Process[F, A]): Process[F, A] = a ++ b
      def point[A](a: => A): Process[F, A] = Process.emit(a)
      def bind[A, B](a: Process[F, A])(f: A => Process[F, B]): Process[F, B] = a flatMap f
    }

  implicit val process1Choice: Choice[Process1] =
    new Choice[Process1] {
      def id[A]: Process1[A, A] = process1.id
      def compose[A, B, C](f: Process1[B, C], g: Process1[A, B]): Process1[A, C] = g |> f
      def choice[A, B, C](f: => Process1[A, C], g: => Process1[B, C]): Process1[A \/ B, C] =
        process1.multiplex(f, g)
    }

  implicit def process1Contravariant[O]: Contravariant[({ type λ[α] = Process1[α, O] })#λ] =
    new Contravariant[({ type λ[α] = Process1[α, O] })#λ] {
      def contramap[A, B](p: Process1[A, O])(f: B => A): Process1[B, O] = p contramap f
    }

  implicit val process1Profunctor: Profunctor[Process1] =
    new Profunctor[Process1] {
      def mapfst[A, B, C](fab: Process1[A, B])(f: C => A): Process1[C, B] = fab contramap f
      def mapsnd[A, B, C](fab: Process1[A, B])(f: B => C): Process1[A, C] = fab map f
    }
}

private trait ProcessHoist extends Hoist[Process] {

  // the monad is actually unnecessary here except to match signatures
  implicit def apply[G[_]: Monad]: Monad[({ type λ[α] = Process[G, α] })#λ] =
    Process.processMonadPlus

  // still unnecessary!
  def liftM[G[_]: Monad, A](a: G[A]): Process[G, A] = Process eval a

  // and more unnecessary constraints...
  def hoist[M[_]: Monad, N[_]](f: M ~> N): ({ type λ[α] = Process[M, α] })#λ ~> ({ type λ[α] = Process[N, α] })#λ =
    new (({ type λ[α] = Process[M, α] })#λ ~> ({ type λ[α] = Process[N, α] })#λ) {
      def apply[A](p: Process[M, A]): Process[N, A] = p translate f
    }
}
