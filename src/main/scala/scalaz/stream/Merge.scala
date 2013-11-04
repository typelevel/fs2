package scalaz.stream

import java.util.concurrent.atomic._
import scalaz.Nondeterminism
import scalaz.\/
import scalaz.\/._

object Merge {

  type Partial[+A] = Throwable \/ A
  import Process._

  def mergeN[F[_]:Nondeterminism,A](p: Process[F, Process[F, A]]): Process[F, A] = {
    def go(k: Key[F, Process[F,A]], ks: Seq[Key[F,A]]): Process[M[F]#Nondeterministic, A] =
      (either(read(k), any(ks))).flatMap { _.fold(
        p => open(p) flatMap (k2 => go(k, ks :+ k2)),
        arem => emit(arem._1) ++ go(k, arem._2)
      )}
    runNondet { open(p) flatMap (go(_, Vector())) }
  }

  private def either[F[_],A,B](a: Process[M[F]#Nondeterministic, A],
                       b: Process[M[F]#Nondeterministic, B]):
                       Process[M[F]#Nondeterministic, A \/ B] = {
    val p1: Process[M[F]#Nondeterministic, Key[F,A]] = open(a)
    val p2: Process[M[F]#Nondeterministic, Key[F,B]] = open(b)
    p1 flatMap (k1 =>
    p2 flatMap (k2 =>
      readEither(k1, k2).repeat // onComplete close(k1) onComplete close(k2)
    ))
  }

  def pipe[F[_],A,B](src: Process[F,A])(f: Process1[A,B]): Process[F,B] = {
    def go(k: Key[F,A], cur: Process1[A,B]): Process[M[F]#Deterministic, B] =
      cur match {
        case h@Halt(_) => close(k)
        case Emit(h, t) => Emit(h, go(k, t))
        case Await1(recv, fb, c) =>
          read(k).flatMap(recv andThen (go(k, _)))
                 .orElse(go(k, fb), go(k, c))
      }
    run { open(src) flatMap (go(_, f)) }
  }

  def tee[F[_],A,B,C](src1: Process[F,A], src2: Process[F,B])(t: Tee[A,B,C]): Process[F,C] = {
    import scalaz.stream.tee.{AwaitL, AwaitR}
    def go(k1: Key[F,A], k2: Key[F,B], cur: Tee[A,B,C]): Process[M[F]#Deterministic, C] =
      cur match {
        case h@Halt(_) => close(k1) onComplete close(k2)
        case Emit(h, t) => Emit(h, go(k1, k2, t))
        case AwaitL(recv, fb, c) =>
          read(k1).flatMap(recv andThen (go(k1, k2, _)))
                  .orElse(go(k1, k2, fb), go(k1, k2, c))
        case AwaitR(recv, fb, c) =>
          read(k2).flatMap(recv andThen (go(k1, k2, _)))
                  .orElse(go(k1, k2, fb), go(k1, k2, c))
      }
    run {
      for {
        k1 <- open(src1)
        k2 <- open(src2)
        c <- go(k1, k2, t)
      } yield c
    }
  }

  def wye[F[_]:Nondeterminism,A,B,C](src1: Process[F,A], src2: Process[F,B])(
                                     y: Wye[A,B,C]): Process[F,C] = {
    import scalaz.stream.wye.{AwaitL, AwaitR, AwaitBoth}
    def go(k1: Key[F,A], k2: Key[F,B], cur: Wye[A,B,C]): Process[M[F]#Nondeterministic, C] =
      cur match {
        case h@Halt(_) => close(k1) onComplete close(k2)
        case Emit(h, t) => Emit(h, go(k1, k2, t))
        case AwaitL(recv, fb, c) =>
          read(k1).flatMap(recv andThen (go(k1, k2, _)))
                  .orElse(go(k1, k2, fb), go(k1, k2, c))
        case AwaitR(recv, fb, c) =>
          read(k2).flatMap(recv andThen (go(k1, k2, _)))
                  .orElse(go(k1, k2, fb), go(k1, k2, c))
        case AwaitBoth(recv, fb, c) =>
          readEither(k1, k2).flatMap(_.fold(
            l => go(k1, k2, recv(These.This(l))),
            r => go(k1, k2, recv(These.That(r)))
          )).orElse(go(k1, k2, fb), go(k1, k2, c))
      }
    runNondet {
      for {
        k1 <- open(src1)
        k2 <- open(src2)
        c <- go(k1, k2, y)
      } yield c
    }
  }

  case class M[-F[_]]() {
    trait Deterministic[+X] extends Nondeterministic[X] {
      def handle[R](algebra: DetA[R]): R
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R = handle(dalg)
    }
    trait Nondeterministic[+X] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R
    }
    case class Open[F2[x]<:F[x],A](s: Process[F2,A]) extends Deterministic[Key[A]] {
      def handle[R](algebra: DetA[R]): R = algebra.open(s)
    }
    case class Close[A](key: Key[A]) extends Deterministic[Nothing] {
      def handle[R](algebra: DetA[R]): R = algebra.close(key)
    }
    case class Read[A](key: Key[A]) extends Deterministic[A] {
      def handle[R](algebra: DetA[R]): R = algebra.read(key)
    }
    case class Any[A](keys: Seq[Key[A]]) extends Nondeterministic[(A, Seq[Key[A]])] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R = nalg.any(keys)
    }
    case class Gather[A](keys: Seq[Key[A]]) extends Nondeterministic[Partial[Seq[A]]] {
      def handle[R](dalg: DetA[R], nalg: NondetA[R]): R = nalg.gather(keys)
    }
    case class GatherUnordered[A](keys: Seq[Key[A]]) extends Nondeterministic[Partial[Seq[A]]] {
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

  def run[F[_],A](p: Process[M[F]#Deterministic, A]): Process[F,A] = {
    def go(ks: Seq[Key[F,Any]], cur: Process[M[F]#Deterministic, A]): Process[F, A] =
      cur match {
        case h@Halt(_) => closeAll(ks) ++ h
        case Emit(h, t) => Emit(h, go(ks, t.asInstanceOf[Process[M[F]#Deterministic, A]]))
        case Await(req, recv, fb, c) => ???
      }
    def closeAll(ks: Seq[Key[F,Any]]): Process[F, Nothing] = ???
    go(Vector(), p)
  }

  def runNondet[F[_]:Nondeterminism,A](p: Process[M[F]#Nondeterministic, A]): Process[F,A] =
    ???

  val M_ = M[Any]()

  def Open[F[_],A](p: Process[F,A]): M[F]#Deterministic[Key[F,A]] =
    M_.Open(p)

  def Close[F[_],A](k: Key[F,A]): M[F]#Deterministic[Nothing] =
    M_.Close(k.asInstanceOf[M_.Key[A]])

  def Read[F[_],A](k: Key[F,A]): M[F]#Deterministic[A] =
    M_.Read(k.asInstanceOf[M_.Key[A]])

  def Any[F[_],A](ks: Seq[Key[F,A]]): M[F]#Nondeterministic[(A, Seq[Key[F,A]])] =
    M_.Any(ks.asInstanceOf[Seq[M_.Key[A]]])

  def Gather[F[_],A](ks: Seq[Key[F,A]]): M[F]#Nondeterministic[Partial[Seq[A]]] =
    M_.Gather(ks.asInstanceOf[Seq[M_.Key[A]]])

  def GatherUnordered[F[_],A](ks: Seq[Key[F,A]]): M[F]#Nondeterministic[Partial[Seq[A]]] =
    M_.GatherUnordered(ks.asInstanceOf[Seq[M_.Key[A]]])

  import Process._

  def open[F[_],A](p: Process[F,A]): Process[M[F]#Deterministic, Key[F,A]] =
    eval(Open(p))

  def close[F[_],A](k: Key[F,A]): Process[M[F]#Deterministic, Nothing] =
    eval(Close(k))

  def read[F[_],A](k: Key[F,A]): Process[M[F]#Deterministic, A] =
    eval(Read(k))

  def any[F[_],A](ks: Seq[Key[F,A]]): Process[M[F]#Nondeterministic, (A, Seq[Key[F,A]])] =
    eval(Any(ks))

  def readEither[F[_],A,B](k1: Key[F,A], k2: Key[F,B]): Process[M[F]#Nondeterministic, A \/ B] = {
    val p1: Process[M[F]#Deterministic, A \/ B] = read(k1) map (left)
    val p2: Process[M[F]#Deterministic, A \/ B] = read(k2) map (right)
    for {
      pk1 <- open(p1): Process[M[F]#Deterministic, Key[F, A \/ B]]
      pk2 <- open(p2): Process[M[F]#Deterministic, Key[F, A \/ B]]
      x <- any(Seq(pk1, pk2))
      _ <- (close(pk1) ++ close(pk2)): Process[M[F]#Deterministic, A \/ B]
    } yield x._1
  }

  def subtyping[F[_],A](p: Process[M[F]#Deterministic,A]): Process[M[F]#Nondeterministic,A] =
    p

  def gather[F[_],A](ks: Seq[Key[F,A]]): Process[M[F]#Nondeterministic, Partial[Seq[A]]] =
    eval(Gather(ks))

  def gatherUnordered[F[_],A](ks: Seq[Key[F,A]]): Process[M[F]#Nondeterministic, Partial[Seq[A]]] =
    eval(GatherUnordered(ks))

  type Key[-F[_],A] = M[F]#Key[A]

    // would be nice if Open didn't have to supply the Process
    // Keys are somewhat unsafe - can be recycled, though I guess
    // calling a closed key can just halt
    // def runMerge[F[_],O](p: Process[M[F,_],O]): Process[F,O]
    // key could store the current state of the process
    // to avoid having an untyped map
}

