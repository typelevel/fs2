package fs2
package fast

import fs2.util.Catenable
import Segment.{TailCall,tailcall,MaxFusionDepth}

// todo -
//   X get rid of depth parameter
//   X add append buffer
//   X pass depth to step function
//   X use exceptions for tail calls
//   support chunks directly

abstract class Segment[+O,+R] { self =>
  type S0
  def s0: S0
  val step: (Int, S0, R => Unit, S0 => Unit, (O, S0) => Unit) => Unit

  def foldLeft[B](z: B)(f: (B,O) => B): B = {
    var s = s0
    var b = z
    var keepGoing = true
    val done = (r: R) => { keepGoing = false }
    val skip = (snew: S0) => { s = snew }
    val emit = (o: O, snew: S0) => { s = snew; b = f(b, o) }
    while (keepGoing) stepTrampolined(0, s, done, skip, emit)
    b
  }

  final def stepTrampolined(
      depth: Int, s0: S0, done: R => Unit, skip: S0 => Unit, emit: (O, S0) => Unit): Unit = {
    try step(depth, s0, done, skip, emit)
    catch { case TailCall(c) =>
      var more = true
      var call = c
      while (more) {
        try { call(); more = false }
        catch { case TailCall(c) => call = c }
      }
    }
  }

  /** Version of `span` which accumulates state. */
  def spans[S](spanState: S)(f: (O,S) => (Boolean,S)): (Chunk[O], Segment[O,R]) = {
    var s = self.s0
    var ss = spanState
    val buf = new collection.mutable.ArrayBuffer[O]
    var keepGoing = true
    var result : Option[R] = None
    while (keepGoing)
      self.stepTrampolined(0, s, r => { keepGoing = false; result = Some(r) }, s0 => s = s0, (o, s0) => {
        val (ok, ss2) = f(o,ss)
        if (ok) { ss = ss2; buf += o; s = s0 }
        else keepGoing = false
      })
    (Chunk.indexedSeq(buf), result match { case None => self.reset(s); case Some(r) => Segment.pure(r) })
  }

  def loop[S1,B,R2>:R](s1: S1)(f: (O, S1, R2 => Unit, S1 => Unit, (B,S1) => Unit) => Unit): Segment[B,R2] =
    new Segment[B,R2] {
      type S0 = (self.S0, S1)
      val s0 = (self.s0, s1)
      val step = (depth, s, done, skip, emit) => {
        val s0 = s._1
        val s1 = s._2
        if (depth < MaxFusionDepth)
          self.step(depth+1, s0, done, s0 => skip((s0, s1)), (o, s0) => {
            f(o, s1, done, s1 => skip((s0,s1)), (b, s1) => emit(b, (s0,s1)))
          })
        else tailcall {
          self.step(0, s0, done, s0 => skip((s0, s1)), (o, s0) => {
            f(o, s1, done, s1 => skip((s0,s1)), (b, s1) => emit(b, (s0,s1)))
          })
        }
      }
    }

  def map[O2](f: O => O2): Segment[O2,R] =
    new Segment[O2,R] {
      type S0 = self.S0
      def s0 = self.s0
      val step = (depth, s, done, skip, emit) =>
        if (depth < MaxFusionDepth)
          self.step(depth+1, s, done, skip, (o, s0) => emit(f(o), s0))
        else
          tailcall { self.step(0, s, done, skip, (o, s0) => emit(f(o), s0)) }
    }

  def filter(f: O => Boolean): Segment[O,R] =
    new Segment[O,R] {
      type S0 = self.S0
      def s0 = self.s0
      val step = (depth, s, done, skip, emit) =>
        if (depth < MaxFusionDepth)
          self.step(depth+1, s, done, skip, (o, s0) => if (f(o)) emit(o, s0) else skip(s0))
        else tailcall {
          self.step(0, s, done, skip, (o, s0) => if (f(o)) emit(o, s0) else skip(s0))
        }
    }

  /** Sets the starting state of the unfold backing this `Segment`. */
  def reset(s: S0): Segment[O,R] = new Segment[O,R] {
    type S0 = self.S0
    def s0 = s
    val step = self.step
  }

  /**
   * `s.splitAt(n)` is equivalent to `(s.take(n).toChunk, s.drop(n))`
   * but avoids traversing the segment twice.
   */
  def splitAt(n: Int): (Chunk[O], Segment[O,R]) =
    spans(n)((o,n) => if (n <= 0) (false,n) else (true,n-1))

  def drop(n: Int): Segment[O,R] =
    self.loop(n)((o,n,done,skip,emit) => if (n <= 0) emit(o,0) else skip(n-1))

  def dropWhile(f: O => Boolean): Segment[O,R] =
    self.loop(())((o,u,done,skip,emit) => if (f(o)) skip(()) else emit(o, ()))

  def mapResult[O2>:O,R2](f: R => R2): Segment[O2,R2] = new Segment[O2,R2] {
    type S0 = self.S0
    val s0 = self.s0
    val step = (depth, s, done, skip, emit) =>
      if (depth < MaxFusionDepth) self.step(depth+1,s,r => done(f(r)),skip,emit)
      else tailcall { self.step(0,s, r => done(f(r)),skip,emit) }
  }

  def void: Segment[O,Unit] = mapResult(_ => ())

  override def toString = self.void.splitAt(10) match {
    case (hd, tl) =>
      "Segment(" + hd.toList.mkString(", ") + (if (tl.uncons.isEmpty) ")" else " ... )")
  }
}

object Segment {

  val empty: Segment[Nothing,Unit] = pure(())

  def pure[R](r: R): Segment[Nothing,R] = new Segment[Nothing,R] {
    type S0 = Unit
    def s0 = ()
    val step = (_,_,done,_,_) => done(r)
  }

  def single[O](o: O): Segment[O,Unit] = new Segment[O,Unit] {
    type S0 = Boolean
    def s0 = true
    val step = (depth,s,done,skip,emit) => if (s) emit(o, false) else done(())
  }

  def apply[O](os: O*): Segment[O,Unit] = seq(os)

  def chunk[O](c: Chunk[O]): Segment[O,Unit] = unfold(c)(_.uncons)

  def seq[O](s: Seq[O]): Segment[O,Unit] = chunk(Chunk.seq(s))

  def indexedSeq[O](s: IndexedSeq[O]): Segment[O,Unit] = chunk(Chunk.indexedSeq(s))

  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O,Unit] = new Segment[O,Unit] {
    type S0 = S
    def s0 = s
    def depth = 0
    val step = (_, s, done, skip, emit) => f(s) match {
      case None => done(())
      case Some((h,t)) => emit(h,t)
    }
  }

  def from(n: Long): Segment[Long,Nothing] = new Segment[Long,Nothing] {
    type S0 = Long
    def s0 = n
    def depth = 0
    val step = (_, n, done, skip, emit) => emit(n, n + 1)
  }

  case class Catenated[O](s0: Catenable[Segment[O,Unit]]) extends Segment[O,Unit] {
    type S0 = Catenable[Segment[O,Unit]]
    val step = (depth, s, done, skip, emit) =>
      if (depth < MaxFusionDepth)
        s.uncons match {
        case None => done(())
        case Some((hd,tl)) =>
          hd.step(depth, hd.s0,
            _ => skip(tl),
            s0 => skip(hd.reset(s0) +: tl),
            (o,s0) => emit(o, hd.reset(s0) +: tl))
      }
      else tailcall { s.uncons match {
        case None => done(())
        case Some((hd,tl)) =>
          hd.step(0, hd.s0,
            _ => skip(tl),
            s0 => skip(hd.reset(s0) +: tl),
            (o,s0) => emit(o, hd.reset(s0) +: tl))
      }}
  }

  implicit class StreamSegment[+O](val self: Segment[O,Unit]) extends AnyVal {

    def uncons: Option[(O,Segment[O,Unit])] = {
      var result: Option[(O, Segment[O,Unit])] = None
      var keepGoing = true
      var s = self.s0
      while (keepGoing) {
        self.stepTrampolined(0, s,
          _ => keepGoing = false,
          s1 => { s = s1 },
          (o, s) => { keepGoing = false; result = Some((o, self.reset(s))) })
      }
      result
    }

    def toChunk: Chunk[O] = {
      val buf = new collection.mutable.ArrayBuffer[O]
      self.foldLeft(())((u,o) => buf += o)
      Chunk.indexedSeq(buf)
    }

    def ++[O2>:O](u: Segment[O2,Unit]): Segment[O2,Unit] = self match {
      case Segment.Catenated(a) => u match {
        case Segment.Catenated(b) => Segment.Catenated(a ++ b)
        case _ => Segment.Catenated(a :+ u)
      }
      case self => u match {
        case Segment.Catenated(b) => Segment.Catenated(self +: b)
        case _ => Segment.Catenated(Catenable(self, u))
      }
    }

    def toScalaStream: scala.Stream[O] = uncons match {
      case None => scala.Stream.empty
      case Some((hd, tl)) => hd #:: tl.toScalaStream
    }

    def memoize: Segment[O,Unit] = Segment.unfold(toScalaStream)(_ match {
      case scala.Stream() => None
      case hd #:: tl => Some((hd, tl))
    })

    def zip[O2](s: Segment[O2,Unit]): Segment[(O,O2),Unit] =
      new Segment[(O,O2),Unit] {
        type S0 = (self.S0, Option[O], s.S0)
        def s0 = (self.s0, None, s.s0)
        val step = (depth, zs, done, skip, emit) => zs._2 match {
          case None =>
            if (depth < MaxFusionDepth)
              self.step(depth+1, zs._1, done, s0 => skip((s0, None, zs._3)),
                                  (o, s0) => skip((s0, Some(o), zs._3)))
            else tailcall {
              self.step(0, zs._1, done, s0 => skip((s0, None, zs._3)),
                                  (o, s0) => skip((s0, Some(o), zs._3)))
            }
          case Some(o) =>
            if (depth < MaxFusionDepth)
              s.step(depth+1, zs._3, done, s2 => skip((zs._1, zs._2, s2)),
                                (o2, s2) => emit((o,o2), (zs._1, None, s2)))
            else tailcall {
              s.step(depth+1, zs._3, done, s2 => skip((zs._1, zs._2, s2)),
                                (o2, s2) => emit((o,o2), (zs._1, None, s2)))
            }
        }
      }

    def take(n: Int): Segment[O,Unit] =
      self.loop(n)((o, n, done, skip, emit) => if (n <= 0) done(()) else emit(o, n-1))

    def takeWhile(f: O => Boolean): Segment[O,Unit] =
      self.loop(())((o,_,done,skip,emit) => if (f(o)) emit(o, ()) else done(()))

    /**
     * `s.span(f)` is equal to `(s.takeWhile(f).toChunk, s.dropWhile(f))`,
     * but has a more efficient implementation.
     */
    def span(f: O => Boolean): (Chunk[O], Segment[O,Unit]) =
      self.spans(())((o,u) => (f(o),u))
  }

  /** The max number of operations that will be fused before trampolining via `[[Segment.tailcall]]`. */
  val MaxFusionDepth = 50

  case class TailCall(call: () => Unit) extends Throwable {
    override def fillInStackTrace = this
    override def toString = "TailCall"
  }

  private[fs2]
  def tailcall(u: => Unit) = throw TailCall(() => u)
}
