package fs2
package fast

import fs2.util.Catenable

class Segment[+O](val sections: Catenable[Segment.F[O]]) extends AnyVal {

  @annotation.tailrec
  final def foldLeft[B](z: B)(f: (B,O) => B): B = sections.uncons match {
    case None => z
    case Some((hd,tl)) => new Segment(tl).foldLeft(hd.foldLeft(z)(f))(f)
  }
}

object Segment {

  val empty: Segment[Nothing] =
    new Segment(Catenable.empty)

  def chunk[O](c: Chunk[O]): Segment[O] =
    new Segment(Catenable.single(Flat(c)))

  def seq[O](s: Seq[O]): Segment[O] =
    chunk(Chunk.seq(s))

  def indexedSeq[O](s: IndexedSeq[O]): Segment[O] =
    chunk(Chunk.indexedSeq(s))

  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O] = new Segment(Catenable(new Unfold[O] {
    type S0 = S
    def s0 = s
    val step = (s, done, skip, emit) => f(s) match {
      case None => done()
      case Some((h,t)) => emit(h,t)
    }
  }))

  def from(n: Long): Segment[Long] = new Segment(Catenable(new Unfold[Long] {
    type S0 = Long
    def s0 = n
    val step = (s, done, skip, emit) => emit(n, n + 1)
  }))

  private[fs2]
  sealed abstract class F[+O] {
    def foldLeft[B](z: B)(f: (B,O) => B): B
    def uncons: Option[(O,F[O])]
    def loop[S,B](s0: S)(f: (O, S, () => Unit, S => Unit, (B,S) => Unit) => Unit): F[B]
    def view: F[O]
  }

  private[fs2]
  case class Flat[+O](chunk: Chunk[O]) extends F[O] {
    def foldLeft[B](z: B)(f: (B,O) => B): B = chunk.foldLeft(z)(f)
    def uncons = chunk.uncons match {
      case None => None
      case Some((hd,tl)) => Some((hd, Flat(tl)))
    }
    def loop[S,B](s0: S)(f: (O, S, () => Unit, S => Unit, (B,S) => Unit) => Unit): F[B] =
      view.loop(s0)(f)
    def view = new Unfold[O] {
      type S0 = Int
      def s0 = 0
      val step = (i, done, skip, emit) => {
        if (i < chunk.size) emit(chunk(i), i + 1)
        else done()
      }
    }
  }

  private[fs2]
  abstract class Unfold[+O] extends F[O] { self =>
    type S0
    def s0: S0
    val step: (S0, () => Unit, S0 => Unit, (O, S0) => Unit) => Unit
    def view = self
    def foldLeft[B](z: B)(f: (B,O) => B): B = {
      var s = s0
      var b = z
      var keepGoing = true
      val done = () => { keepGoing = false }
      val skip = (snew: S0) => { s = snew }
      val emit = (o: O, snew: S0) => { s = snew; b = f(b, o) }
      while (keepGoing) step(s, done, skip, emit)
      b
    }
    // todo probably can do something clever to prevent depth from getting too high
    // even if ++ are unbalanced or there's like 100 loops in a row..
    // track depth of Unfold, collapse if > 20, and collapse self or u if size more than
    // double the other side
    // todo consider Unfold[Chunk[O2]] as representation for Segment
    def ++[O2>:O](u: Unfold[O2]): Unfold[O2] = new Unfold[O2] {
      type S0 = Either[self.S0, u.S0]
      def s0 = Left(self.s0)
      val step = (se, done, skip, emit) => se match {
        case Left(s0) => self.step(s0, () => skip(Right(u.s0)), s0 => skip(Left(s0)), (o2,s0) => emit(o2, Left(s0)))
        case Right(s1) => u.step(s1, done, s1 => skip(Right(s1)), (o2,s1) => emit(o2, Right(s1)))
      }
    }
    def loop[S1,B](s1: S1)(f: (O, S1, () => Unit, S1 => Unit, (B,S1) => Unit) => Unit): F[B] = new Unfold[B] {
      type S0 = (self.S0, S1)
      val s0 = (self.s0, s1)
      val step = (s, done, skip, emit) => {
        val s0 = s._1
        val s1 = s._2
        self.step(s0, done, s0 => skip((s0, s1)), (o: O, s0) => {
          f(o, s1, done, s1 => skip((s0,s1)), (b, s1) => emit(b, (s0,s1)))
        })
      }
    }
    def uncons = {
      var result: Option[(O, F[O])] = None
      var keepGoing = true
      var s = s0
      while (keepGoing) {
        step(s, () => keepGoing = false,
                s1 => { s = s1 },
                (o, s) => { keepGoing = false;
                            val tl = new Unfold[O] { type S0 = self.S0; def s0 = s; val step = self.step }
                            result = Some((o, tl)) })
      }
      result
    }
  }
}
