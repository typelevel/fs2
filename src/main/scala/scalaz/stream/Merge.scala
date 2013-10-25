package scalaz.stream

import java.util.concurrent.atomic._
import scalaz.Nondeterminism
import scalaz.\/

object Merge {

  type Partial[+A] = Throwable \/ A

  def mergeN[F[_]:Nondeterminism,A](p: Process[F, Process[F, A]]): Process[F, A] = ???

  def tee[F[_],A,B,C](p1: Process[F,A], p2: Process[F,B])(t: Tee[A,B,C]): Process[F,C] =
    // open p1, open p2, the transform `t` -
    //   requests from the left issue a read from `p1`
    //   requests from the right issue a read from `p2`
    //   `t.onComplete(close(p1) ++ close(p2))`.
    ???

  case class M[-F[_]]() {
    trait Deterministic[+X] {
      def handle[R](algebra: DetA[R]): R
    }
    trait Nondeterministic[+X] extends Deterministic[X] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R
      def handle[R](dalg: DetA[R]): R = handle(dalg, null)
    }
    case class Open[F2[x]<:F[x],A](s: Process[F2,A]) extends Deterministic[Key[A]] {
      def handle[R](algebra: DetA[R]): R = algebra.open(s)
    }
    case class Close[A](key: Key[A]) extends Deterministic[Nothing] {
      def handle[R](algebra: DetA[R]): R = algebra.close(key)
    }
    case class Read[A](key: Key[A]) extends Deterministic[Partial[A]] {
      def handle[R](algebra: DetA[R]): R = algebra.read(key)
    }
    case class Any[A](keys: Seq[Key[A]]) extends Nondeterministic[Partial[A]] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R = nalg.any(keys)
    }
    case class Gather[A](keys: Seq[Key[A]]) extends Nondeterministic[Seq[Partial[A]]] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R = nalg.gather(keys)
    }
    case class GatherUnordered[A](keys: Seq[Key[A]]) extends Nondeterministic[Seq[Partial[A]]] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R = nalg.gatherUnordered(keys)
    }

    trait DetA[+R] {
      def open[A](s: Process[F,A]): R
      def close[A](k: Key[A]): R
      def read[A](k: Key[A]): R
    }

    trait NondetA[+R] {
      def any[A](ks: Seq[Key[A]]): R
      def gather[A](ks: Seq[Key[A]]): R
      def gatherUnordered[A](ks: Seq[Key[A]]): R
    }

    class Key[A] private[stream](private[stream] ref: AtomicReference[Process[F,A]]) {
      private[stream] def set(p: Process[F,A]): Unit = ref.set(p)
    }
    object Key {
      private[stream] def apply[A](p: Process[F,A]): Key[A] =
        new Key(new AtomicReference(p))
    }
  }

  def run[F[_],A](p: Process[M[F]#Deterministic, A]): Process[F,A] =
    ???

  def runNondet[F[_]:Nondeterminism,A](p: Process[M[F]#Nondeterministic, A]): Process[F,A] =
    ???

  val M_ = M[Any]()

  def Open[F[_],A](p: Process[F,A]): M[F]#Deterministic[M[F]#Key[A]] =
    M_.Open(p)

  def Close[F[_],A](k: M[F]#Key[A]): M[F]#Deterministic[Nothing] =
    M_.Close(k.asInstanceOf[M_.Key[A]])

  def Read[F[_],A](k: M[F]#Key[A]): M[F]#Deterministic[Partial[A]] =
    M_.Read(k.asInstanceOf[M_.Key[A]])

  def Any[F[_],A](ks: Seq[M[F]#Key[A]]): M[F]#Nondeterministic[Partial[A]] =
    M_.Any(ks.asInstanceOf[Seq[M_.Key[A]]])

  def Gather[F[_],A](ks: Seq[M[F]#Key[A]]): M[F]#Nondeterministic[Seq[Partial[A]]] =
    M_.Gather(ks.asInstanceOf[Seq[M_.Key[A]]])

  def GatherUnordered[F[_],A](ks: Seq[M[F]#Key[A]]): M[F]#Nondeterministic[Seq[Partial[A]]] =
    M_.GatherUnordered(ks.asInstanceOf[Seq[M_.Key[A]]])

  import Process._

  def open[F[_],A](p: Process[F,A]): Process[M[F]#Deterministic, M[F]#Key[A]] =
    eval(Open(p))

  def close[F[_],A](k: M[F]#Key[A]): Process[M[F]#Deterministic, Nothing] =
    eval(Close(k))

  def read[F[_],A](k: M[F]#Key[A]): Process[M[F]#Deterministic, Partial[A]] =
    eval(Read(k))

  def any[F[_],A](ks: Seq[M[F]#Key[A]]): Process[M[F]#Nondeterministic, Partial[A]] =
    eval(Any(ks))

  def gather[F[_],A](ks: Seq[M[F]#Key[A]]): Process[M[F]#Nondeterministic, Seq[Partial[A]]] =
    eval(Gather(ks))

  def gatherUnordered[F[_],A](ks: Seq[M[F]#Key[A]]): Process[M[F]#Nondeterministic, Seq[Partial[A]]] =
    eval(GatherUnordered(ks))

    // would be nice if Open didn't have to supply the Process
    // Keys are somewhat unsafe - can be recycled, though I guess
    // calling a closed key can just halt
    // def runMerge[F[_],O](p: Process[M[F,_],O]): Process[F,O]
    // key could store the current state of the process
    // to avoid having an untyped map
}
