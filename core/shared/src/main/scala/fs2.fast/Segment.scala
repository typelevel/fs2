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

  def map[O2](f: O => O2): Segment[O2] = new Segment[O2] {
    type S0 = self.S0
    val depth = self.depth + 1
    def s0 = self.s0
    val step = (s, done, skip, emit) =>
      self.step(s, done, skip, (o, s0) => emit(f(o), s0))
  }

  def uncons = {
    var result: Option[(O, Segment[O])] = None
    var keepGoing = true
    var s = s0
    while (keepGoing) {
      step(s, () => keepGoing = false,
              s1 => { s = s1 },
              (o, s) => { keepGoing = false;
                          val tl = new Segment[O] {
                            type S0 = self.S0
                            def s0 = s
                            val depth = self.depth
                            val step = self.step
                          }
                          result = Some((o, tl)) })
    }
    result
  }

  def toChunk: Chunk[O] = {
    val buf = new collection.mutable.ArrayBuffer[O]
    foldLeft(())((u,o) => buf += o)
    Chunk.indexedSeq(buf)
  }

  override def toString = "Segment(" + this.take(10).toChunk.toList.mkString(", ") + ")"
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
