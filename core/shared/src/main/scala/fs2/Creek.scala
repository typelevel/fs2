package fs2

import cats.{Eval, Monad}

import Creek.Pull

final class Creek[+O](private val pull: Pull[O, Unit]) { self =>

  def ++[O2 >: O](that: => Creek[O2]): Creek[O2] =
    (pull >> that.pull).creek

  def append[O2 >: O](that: => Creek[O2]): Creek[O2] = this ++ that

  def drain: Creek[Nothing] = mapChunks(_ => Chunk.empty)

  def flatMap[O2](f: O => Creek[O2]): Creek[O2] =
    pull.flatMapOutput(o => f(o).pull).creek

  def fold[A](init: A)(f: (A, Chunk[O]) => A): A =
    pull.fold(init)(f)._2

  def map[O2](f: O => O2): Creek[O2] =
    mapChunks(_.map(f))

  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Creek[O2] =
    pull.mapOutput(f).creek

  def repeat: Creek[O] = this ++ repeat

  def take(n: Int): Creek[O] =
    pull.take(n).void.creek

  def to(collector: Collector[O]): collector.Out =
    fold(collector.newBuilder) { (bldr, c) => bldr += c; bldr }.result

  def uncons: Pull[Nothing, Option[(Chunk[O], Creek[O])]] =
    pull.uncons.map {
      case Left(_)         => None
      case Right((hd, tl)) => Some((hd, tl.creek))
    }
}

object Creek {
  def apply[O](os: O*): Creek[O] = emits(os)

  def chunk[O](os: Chunk[O]): Creek[O] =
    Pull.output(os).creek

  def emit[O](o: O): Creek[O] = chunk(Chunk.singleton(o))

  def emits[O](os: scala.collection.Seq[O]): Creek[O] =
    os match {
      case Nil    => empty
      case Seq(x) => emit(x)
      case _      => chunk(Chunk.seq(os))
    }

  val empty: Creek[Nothing] = Pull.done.creek

  def iterate[A](start: A)(f: A => A): Creek[A] =
    emit(start) ++ iterate(f(start))(f)

  def integers: Creek[Int] = iterate(0)(_ + 1)

  implicit def monadInstance[O]: Monad[Creek] =
    new Monad[Creek] {
      def pure[A](a: A) = emit(a)
      def flatMap[A, B](fa: Creek[A])(f: A => Creek[B]) = fa.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Creek[Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => emit(b)
        }
    }

  sealed trait Pull[+O, +R] { self =>
 
    protected def step[O2 >: O, R2 >: R]: Eval[Either[R2, (Chunk[O2], Pull[O2, R2])]]

    def creek(implicit ev: R <:< Unit): Creek[O] = new Creek(this.asInstanceOf[Pull[O, Unit]])

    def flatMap[O2 >: O, R2](f: R => Pull[O2, R2]): Pull[O2, R2] =
      new Pull[O2, R2] {
        protected def step[O3 >: O2, R3 >: R2]: Eval[Either[R3, (Chunk[O3], Pull[O3, R3])]] =
          Eval.defer(self.step[O3, R]).flatMap {
            case Right((hd, tl)) => 
              Eval.now(Right((hd, tl.flatMap(f))))
            case Left(r)         => f(r).step
          }


      }

    def >>[O2 >: O, R2](that: => Pull[O2, R2]): Pull[O2, R2] = flatMap(_ => that)

    def flatMapOutput[O2](f: O => Pull[O2, Unit])(implicit ev: R <:< Unit): Pull[O2, Unit] =
      new Pull[O2, Unit] {
        protected def step[O3 >: O2, R2 >: Unit] =
          Eval.defer(self.step[O, R]).flatMap {
            case Right((hd, tl)) =>
              tl match {
                case _: Pull.Result[_] if hd.size == 1 =>
                  f(hd(0)).step
                case _ =>
                  def go(idx: Int): Pull[O2, Unit] =
                    if (idx == hd.size) tl.flatMapOutput(f)
                    else f(hd(idx)) >> go(idx + 1)
                  go(0).step
              }
            case Left(_) => Eval.now(Left(()))
          }
      }

    def fold[A, O2 >: O, R2 >: R](init: A)(f: (A, Chunk[O]) => A): (R2, A) = {
      def go(init: A, p: Pull[O, R]): Eval[(R2, A)] =
        p.step[O, R].flatMap {
          case Right((hd, tl)) => go(f(init, hd), tl)
          case Left(r)         => Eval.now((r, init))
        }
      go(init, this).value
    }

    def mapOutput[O2](f: Chunk[O] => Chunk[O2]): Pull[O2, R] = new Pull[O2, R] {
      protected def step[O3 >: O2, R2 >: R] =
        self.step[O, R].map(_.map { case (hd, tl) => (f(hd), tl.mapOutput(f)) })
    }

    def take(n: Int): Pull[O, Option[R]] = {
      def go(n: Int, p: Pull[O, R]): Pull[O, Option[R]] =
        if (n <= 0) Pull.pure(None)
        else
          p.uncons.flatMap {
            case Right((hd, tl)) =>
              Pull.output(hd.take(n)) >> go(n - hd.size, tl)
            case Left(r) => Pull.pure(Some(r))
          }
      go(n, self)
    }

    def uncons: Pull[Nothing, Either[R, (Chunk[O], Pull[O, R])]] =
      Pull.pure(self.step.value)

    def map[R2](f: R => R2): Pull[O, R2] = flatMap(r => Pull.pure(f(r)))
    def void: Pull[O, Unit] = map(_ => ())
  }

  object Pull {
    def pure[R](r: R): Pull[Nothing, R] = new Result(r)
    private class Result[R](r: R) extends Pull[Nothing, R] {
      protected def step[O2 >: Nothing, R2 >: R] = Eval.now(Left(r))
    }

    def output[O](c: Chunk[O]): Pull[O, Unit] =
      new Pull[O, Unit] {
        protected def step[O2 >: O, R2 >: Unit] = Eval.now(Right((c, done)))
      }

    val done: Pull[Nothing, Unit] = pure(())

    implicit def monadInstance[O]: Monad[Pull[O, ?]] =
      new Monad[Pull[O, ?]] {
        def pure[A](a: A) = Pull.pure(a)
        def flatMap[A, B](fa: Pull[O, A])(f: A => Pull[O, B]) = fa.flatMap(f)
        def tailRecM[A, B](a: A)(f: A => Pull[O, Either[A, B]]) =
          f(a).flatMap {
            case Left(a)  => tailRecM(a)(f)
            case Right(b) => pure(b)
          }
      }
  }
}
