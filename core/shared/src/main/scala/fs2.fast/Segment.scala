package fs2
package fast

abstract class Segment[+O] { self =>
  type S0
  def s0: S0
  val step: (S0, () => Unit, S0 => Unit, (O, S0) => Unit) => Unit
  def depth: Int

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

  def uncons = {
    var result: Option[(O, Segment[O])] = None
    var keepGoing = true
    var s = s0
    while (keepGoing) {
      step(s, () => keepGoing = false,
              s1 => { s = s1 },
              (o, s) => { keepGoing = false; result = Some((o, self.reset(s))) })
    }
    result
  }

  def toChunk: Chunk[O] = {
    val buf = new collection.mutable.ArrayBuffer[O]
    foldLeft(())((u,o) => buf += o)
    Chunk.indexedSeq(buf)
  }

  def ++[O2>:O](u: Segment[O2]): Segment[O2] = unbalancedAppend(u)

  private[fs2]
  def unbalancedAppend[O2>:O](u: Segment[O2]): Segment[O2] = new Segment[O2] { lassoc =>
    type S0 = Either[self.S0, u.S0]
    def s0 = Left(self.s0)
    val depth = (self.depth max u.depth) + 1
    val step = (se, done, skip, emit) => se match {
      case Left(s0) => self.step(s0, () => skip(Right(u.s0)), s0 => skip(Left(s0)), (o2,s0) => emit(o2, Left(s0)))
      case Right(s1) => u.step(s1, done, s1 => skip(Right(s1)), (o2,s1) => emit(o2, Right(s1)))
    }
    override def ++[O3>:O2](u2: Segment[O3]): Segment[O3] = {
      if ((self.depth - u.depth).abs > (u.depth - u2.depth).abs)
        self unbalancedAppend (u ++ u2)
      else
        lassoc unbalancedAppend u2
    }
  }

  def loop[S1,B](s1: S1)(f: (O, S1, () => Unit, S1 => Unit, (B,S1) => Unit) => Unit): Segment[B] =
    new Segment[B] {
      type S0 = (self.S0, S1)
      val s0 = (self.s0, s1)
      val depth = self.depth + 1
      val step = (s, done, skip, emit) => {
        val s0 = s._1
        val s1 = s._2
        self.step(s0, done, s0 => skip((s0, s1)), (o, s0) => {
          f(o, s1, done, s1 => skip((s0,s1)), (b, s1) => emit(b, (s0,s1)))
        })
      }
    }

  def take(n: Int): Segment[O] =
    loop(n)((o, n, done, skip, emit) => if (n <= 0) done() else emit(o, n-1))

  def takeWhile(f: O => Boolean): Segment[O] =
    loop(())((o,_,done,skip,emit) => if (f(o)) emit(o, ()) else done())

  def filter(f: O => Boolean): Segment[O] = new Segment[O] {
    type S0 = self.S0
    val depth = self.depth + 1
    def s0 = self.s0
    val step = (s, done, skip, emit) =>
      self.step(s, done, skip, (o, s0) => if (f(o)) emit(o, s0) else skip(s0))
  }

  def zip[O2](s: Segment[O2]): Segment[(O,O2)] = new Segment[(O,O2)] {
    type S0 = (self.S0, Option[O], s.S0)
    val depth = s.depth + self.depth + 1
    def s0 = (self.s0, None, s.s0)
    val step = (zs, done, skip, emit) => zs._2 match {
      case None => self.step(zs._1, done, s0 => skip((s0, None, zs._3)),
                            (o, s0) => skip((s0, Some(o), zs._3)))
      case Some(o) => s.step(zs._3, done, s2 => skip((zs._1, zs._2, s2)),
                            (o2, s2) => emit((o,o2), (zs._1, None, s2)))
    }
  }

  def map[O2](f: O => O2): Segment[O2] = new Segment[O2] {
    type S0 = self.S0
    val depth = self.depth + 1
    def s0 = self.s0
    val step = (s, done, skip, emit) =>
      self.step(s, done, skip, (o, s0) => emit(f(o), s0))
  }

  /** Version of `span` which accumulates state. */
  def spans[S](spanState: S)(f: (O,S) => (Boolean,S)): (Chunk[O], Segment[O]) = {
    var s = self.s0
    var ss = spanState
    val buf = new collection.mutable.ArrayBuffer[O]
    var keepGoing = true
    while (keepGoing)
      self.step(s, () => keepGoing = false, s0 => s = s0, (o, s0) => {
        val (ok, ss2) = f(o,ss)
        if (ok) { ss = ss2; buf += o; s = s0 }
        else keepGoing = false
      })
    (Chunk.indexedSeq(buf), self.reset(s))
  }

  /**
   * `s.span(f)` is equal to `(s.takeWhile(f).toChunk, s.dropWhile(f))`,
   * but has a more efficient implementation.
   */
  def span(f: O => Boolean): (Chunk[O], Segment[O]) =
    spans(())((o,u) => (f(o),u))

  def drop(n: Int): Segment[O] =
    loop(n)((o,n,done,skip,emit) => if (n <= 0) emit(o,0) else skip(n-1))

  def dropWhile(f: O => Boolean): Segment[O] =
    loop(())((o,u,done,skip,emit) => if (f(o)) skip(()) else emit(o, ()))

  /** Sets the starting state of the unfold backing this `Segment`. */
  def reset(s: S0): Segment[O] = new Segment[O] {
    type S0 = self.S0
    def s0 = s
    val depth = self.depth
    val step = self.step
  }

  /**
   * `s.splitAt(n)` is equivalent to `(s.take(n).toChunk, s.drop(n))`
   * but avoids traversing the segment twice.
   */
  def splitAt(n: Int): (Chunk[O], Segment[O]) =
    spans(n)((o,n) => if (n <= 0) (false,n) else (true,n-1))

  override def toString = splitAt(10) match {
    case (hd, tl) =>
      "Segment(" + hd.toList.mkString(", ") + (if (tl.uncons.isEmpty) ")" else " ... )")
  }
}

object Segment {

  val empty: Segment[Nothing] = new Segment[Nothing] {
    type S0 = Unit
    def s0 = ()
    def depth = 0
    val step = (_,done,_,_) => done()
  }

  def single[O](o: O): Segment[O] = new Segment[O] {
    type S0 = Boolean
    def s0 = true
    def depth = 0
    val step = (s,done,skip,emit) => if (s) emit(o, false) else done()
  }

  def apply[O](os: O*): Segment[O] = seq(os)

  def chunk[O](c: Chunk[O]): Segment[O] = ???

  def seq[O](s: Seq[O]): Segment[O] = chunk(Chunk.seq(s))

  def indexedSeq[O](s: IndexedSeq[O]): Segment[O] = chunk(Chunk.indexedSeq(s))

  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O] = new Segment[O] {
    type S0 = S
    def s0 = s
    def depth = 0
    val step = (s, done, skip, emit) => f(s) match {
      case None => done()
      case Some((h,t)) => emit(h,t)
    }
  }

  def from(n: Long): Segment[Long] = new Segment[Long] {
    type S0 = Long
    def s0 = n
    def depth = 0
    val step = (n, done, skip, emit) => emit(n, n + 1)
  }
}
