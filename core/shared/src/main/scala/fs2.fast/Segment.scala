package fs2.fast

import fs2.Chunk
import fs2.util.Catenable
import Segment._
import cats.Eval

abstract class Segment[+O,+R] { self =>
  private[fs2]
  def bind0: (Depth, O => Unit, Chunk[O] => Unit, R => Unit) => Step[O,R]

  final def bind: (Depth, O => Unit, Chunk[O] => Unit, R => Unit) => Step[O,R] =
    (depth, emit, emits, done) =>
      if (depth < MaxFusionDepth) bind0(depth + 1, emit, emits, done)
      else {
        val s = bind0(0, emit, emits, done)
        Step(s.remainder0, () => throw TailCall(s.step))
      }

  final def uncons: Either[R, (Chunk[O],Segment[O,R])] = {
    var result: Either[R,Chunk[O]] = null
    var ok = true
    val step = bind(0,
      o => { result = Right(Chunk.singleton(o)); ok = false },
      os => { result = Right(Chunk.indexedSeq(os.toVector)); ok = false }, // todo use array copy
      r => { result = Left(r); ok = false })
    while (ok) step()
    result match {
      case Right(c) => Right(c -> step.remainder)
      case l@Left(_) => l.asInstanceOf
    }
  }

  final def run: R = {
    var result: Option[R] = None
    var ok = true
    val step = bind(0, _ => (), _ => (), r => { result = Some(r); ok = false })
    while (ok) step()
    result.get
  }

  final def sum[N>:O](initial: N)(implicit N: Numeric[N]): Segment[Nothing,N] = new Segment[Nothing,N] {
    def bind0 = (depth, emit, emits, done) => {
      var b = N.zero
      self.bind(depth,
        o => b = N.plus(b, o),
        { case os : Chunk.Longs =>
            var i = 0
            var cs = 0L
            while (i < os.size) { cs += os.at(i); i += 1 }
            b = N.plus(b, cs.asInstanceOf[N])
          case os =>
            var i = 0
            while (i < os.size) { b = N.plus(b, os(i)); i += 1 }
        },
        r => done(b)).tweak(_.sum(b))
    }
  }

  final def fold[B](z: B)(f: (B,O) => B): Segment[Nothing,B] = new Segment[Nothing,B] {
    def bind0 = (depth, emit, emits, done) => {
      var b = z
      self.bind(depth,
        o => b = f(b, o),
        os => { var i = 0; while (i < os.size) { b = f(b, os(i)); i += 1 } },
        r => done(b)).tweak(_.fold(b)(f))
    }
  }

  final def scan[B](z: B)(f: (B,O) => B): Segment[B,B] = new Segment[B,B] {
    def bind0 = (depth, emit, emits, done) => {
      var b = z
      self.bind(depth,
        o => { emit(b); b = f(b, o) },
        os => { var i = 0; while (i < os.size) { emit(b); b = f(b, os(i)); i += 1 } },
        r => { emit(b); done(b) }).tweak(_.scan(b)(f))
    }
  }

  final def take(n: Long): Segment[O,Option[(R,Long)]] = new Segment[O,Option[(R,Long)]] {
    def bind0 = (depth, emit, emits, done) => {
      var rem = n
      self.bind(depth + 1,
        o => { if (rem > 0) { rem -= 1; emit(o) } else done(None) },
        os => { if (os.size <= rem) { rem -= ChunkSize; emits(os) }
                else {
                  var i = 0
                  while (rem > 0) { rem -= 1; emit(os(i)); i += 1 }
                  done(None)
                }
              },
        r => done(Some(r -> rem))
      ).tweak(_.take(rem))
    }
  }

  final def map[O2](f: O => O2): Segment[O2,R] = new Segment[O2,R] {
    def bind0 = (depth, emit, emits, done) =>
      self.bind(depth + 1,
        o => emit(f(o)),
        os => { var i = 0; while (i < os.size) { emit(f(os(i))); i += 1; } },
        done).tweak(_ map f)
  }

  final def ++[O2>:O,R2>:R](s2: Segment[O2,R2]): Segment[O2,R2] = new Segment[O2,R2] {
    def bind0 = (depth, emit, emits, done) => {
      val b2 = s2.bind(depth + 1, emit, emits, done)
      var s: Step[O2,R2] = null
      var b1 = self.bind(depth + 1, emit, emits, _ => s = b2).tweak(_ ++ s2)
      s = b1
      step(s.remainder) { s() }
    }
  }

  final def push[O2>:O](c: Chunk[O2]): Segment[O2,R] = new Segment[O2,R] {
    def bind0 = (depth, emit, emits, done) => {
      self.bind(depth + 1, emit, emits, r => { emits(c); done(r) }).tweak(_ push c)
    }
  }

  final def toChunk: Chunk[O] = {
    val buf = new collection.mutable.ArrayBuffer[O]
    self.map { o => buf += o; () }.run
    Chunk.indexedSeq(buf)
  }

  /**
   * `s.splitAt(n)` is equivalent to `(s.take(n).toChunk, s.drop(n))`
   * but avoids traversing the segment twice.
   */
  def splitAt(n: Int): (Chunk[O], Segment[O,R]) = {
    def go(n: Int, acc: Catenable[Chunk[O]], seg: Segment[O,R]): (Chunk[O], Segment[O,R]) = {
      seg.uncons match {
        case Left(r) => (Chunk.concat(acc.toList), Segment.pure(r))
        case Right((chunk,rem)) =>
          chunk.size match {
            case sz if n == sz => (Chunk.concat((acc :+ chunk).toList), rem)
            case sz if n < sz => (chunk.take(n), rem.push(chunk.drop(n)))
            case sz => go(n - chunk.size, acc :+ chunk, rem)
          }
      }
    }
    go(n, Catenable.empty, this)
  }
}

object Segment {
  val empty: Segment[Nothing,Unit] = pure(())

  def pure[R](r: R): Segment[Nothing,R] = new Segment[Nothing,R] {
    def bind0 = (_,_,_,done) => step(pure(r))(done(r))
  }

  def single[O](o: O): Segment[O,Unit] = new Segment[O,Unit] {
    def bind0 = (_, emit, _, done) => step(empty) { emit(o); done(()) }
  }

  def chunk[O](os: Chunk[O]): Segment[O,Unit] = new Segment[O,Unit] {
    def bind0 = (_, _, emits, done) => step(empty) { emits(os); done(()) }
  }

  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O,Unit] = new Segment[O,Unit] {
    def bind0 = (depth, emit, emits, done) => {
      var s0 = s
      step(empty) {
        f(s0) match {
          case None => done(())
          case Some((h,t)) => emit(h); s0 = t
        }
      }
    }
  }

  def step[O,R](rem: => Segment[O,R])(s: => Unit): Step[O,R] =
    Step(Eval.always(rem), () => s)

  case class Step[+O,+R](remainder0: Eval[Segment[O,R]], step: () => Unit) {
    final def apply(): Unit = stepSafely(this)
    final def remainder: Segment[O,R] = remainder0.value
    final def tweak[O2,R2](f: Segment[O,R] => Segment[O2,R2]): Step[O2,R2] =
      Step(remainder0 map f, step)
  }

  def from(n: Long, by: Long = 1): Segment[Long,Nothing] = new Segment[Long,Nothing] {
    def bind0 = (_, _, emits, _) => {
      var m = n
      var buf = new Array[Long](ChunkSize)
      step(from(m,by)) {
        var i = 0
        while (i < buf.length) { buf(i) = m; m += by; i += 1 }
        emits(Chunk.longs(buf))
      }
    }
  }

  val MaxFusionDepth = 50
  val ChunkSize = 32

  def stepSafely(t: Step[Any,Any]): Unit = {
    try t.step()
    catch { case TailCall(t) =>
      var tc = t
      while(true) {
        try { tc(); return () }
        catch { case TailCall(t) => tc = t }
      }
    }
  }

  case class TailCall(k: () => Any) extends Throwable {
    override def fillInStackTrace = this
  }

  type Depth = Int
}
