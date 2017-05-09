package fs2.fast

import cats.Eval
import fs2.Chunk
import fs2.util.Catenable

import Segment._

abstract class Segment[+O,+R] { self =>
  private[fs2]
  def stage0: (Depth, (=> Unit) => Unit, O => Unit, Chunk[O] => Unit, R => Unit) => Eval[Step[O,R]]

  private[fs2]
  final def stage: (Depth, (=> Unit)=>Unit, O => Unit, Chunk[O] => Unit, R => Unit) => Eval[Step[O,R]] =
    (depth, defer, emit, emits, done) =>
      if (depth < MaxFusionDepth) stage0(depth.increment, defer, emit, emits, done)
      else Eval.defer {
        stage0(Depth(0), defer,
               o => defer(emit(o)),
               os => defer(emits(os)),
               r => defer(done(r)))
      }

  final def uncons: Either[R, (Chunk[O],Segment[O,R])] = {
    var out: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[R] = None
    var ok = true
    val trampoline = makeTrampoline
    val step = stage(Depth(0),
      defer(trampoline),
      o => { out = out :+ Chunk.singleton(o); ok = false },
      os => { out = out :+ Chunk.indexedSeq(os.toVector); ok = false }, // todo use array copy
      r => { result = Some(r); ok = false }).value
    while (ok) steps(step, trampoline)
    result match {
      case None => Right(Chunk.concat(out.toList) -> step.remainder)
      case Some(r) =>
        if (out.isEmpty) Left(r)
        else Right(Chunk.concat(out.toList) -> pure(r))
    }
  }

  final def run[O2>:O](implicit U: O2 =:= Unit): R = {
    var result: Option[R] = None
    var ok = true
    val trampoline = makeTrampoline
    val step = stage(Depth(0), defer(trampoline), _ => (), _ => (), r => { result = Some(r); ok = false }).value
    while (ok) steps(step, trampoline)
    result.get
  }

  final def sum[N>:O](initial: N)(implicit N: Numeric[N]): Segment[Nothing,N] = new Segment[Nothing,N] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var b = N.zero
      self.stage(depth.increment, defer,
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
        r => done(b)).map(_.mapRemainder(_.sum(b)))
    }
    override def toString = s"($self).sum($initial)"
  }

  final def fold[B](z: B)(f: (B,O) => B): Segment[Nothing,B] = new Segment[Nothing,B] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var b = z
      self.stage(depth.increment, defer,
        o => b = f(b, o),
        os => { var i = 0; while (i < os.size) { b = f(b, os(i)); i += 1 } },
        r => done(b)).map(_.mapRemainder(_.fold(b)(f)))
    }
    override def toString = s"($self).fold($z)($f)"
  }

  final def scan[B](z: B)(f: (B,O) => B): Segment[B,B] = new Segment[B,B] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var b = z
      self.stage(depth.increment, defer,
        o => { emit(b); b = f(b, o) },
        os => { var i = 0; while (i < os.size) { emit(b); b = f(b, os(i)); i += 1 } },
        r => { emit(b); done(b) }).map(_.mapRemainder(_.scan(b)(f)))
    }
    override def toString = s"($self).scan($z)($f)"
  }

  final def take(n: Long): Segment[O,Option[(R,Long)]] = new Segment[O,Option[(R,Long)]] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var rem = n
      self.stage(depth.increment, defer,
        o => { if (rem > 0) { rem -= 1; emit(o) } else done(None) },
        os => { if (os.size <= rem) { rem -= os.size; emits(os) }
                else {
                  var i = 0
                  while (rem > 0) { rem -= 1; emit(os(i)); i += 1 }
                  done(None)
                }
              },
        r => done(Some(r -> rem))
      ).map(_.mapRemainder(_.take(rem)))
    }
    override def toString = s"($self).take($n)"
  }

  final def map[O2](f: O => O2): Segment[O2,R] = new Segment[O2,R] {
    def stage0 = (depth, defer, emit, emits, done) => Eval.defer {
      self.stage(depth.increment, defer,
        o => emit(f(o)),
        os => { var i = 0; while (i < os.size) { emit(f(os(i))); i += 1; } },
        done).map(_.mapRemainder(_ map f))
    }
    override def toString = s"($self).map($f)"
  }

  final def ++[O2>:O,R2>:R](s2: Segment[O2,R2]): Segment[O2,R2] = this match {
    case Catenated(s1s) => s2 match {
      case Catenated(s2s) => Catenated(s1s ++ s2s)
      case _ => Catenated(s1s :+ s2)
    }
    case s1 => s2 match {
      case Catenated(s2s) => Catenated(s1 +: s2s)
      case s2 => Catenated(Catenable(s1,s2))
    }
  }

  final def push[O2>:O](c: Chunk[O2]): Segment[O2,R] =
    // note - cast is fine, as `this` is guaranteed to provide an `R`,
    // overriding the `Unit` produced by `this`
    chunk(c).asInstanceOf[Segment[O2,R]] ++ this

  final def foreachChunk(f: Chunk[O] => Unit): Unit = {
    var ok = true
    val trampoline = makeTrampoline
    val step = stage(Depth(0), defer(trampoline), o => f(Chunk.singleton(o)), f, r => { ok = false }).value
    while (ok) steps(step, trampoline)
  }

  final def toChunks: Catenable[Chunk[O]] = {
    var acc: Catenable[Chunk[O]] = Catenable.empty
    foreachChunk(c => acc = acc :+ c)
    acc
  }

  final def toChunk: Chunk[O] = Chunk.concat(toChunks.toList)

  /**
   * `s.splitAt(n)` is equivalent to `(s.take(n).toChunk, s.drop(n))`
   * but avoids traversing the segment twice.
   */
  def splitAt(n: Int): (Chunk[O], Segment[O,R]) = {
    // TODO rewrite this as an interpreter
    @annotation.tailrec
    def go(n: Int, acc: Catenable[Chunk[O]], seg: Segment[O,R]): (Chunk[O], Segment[O,R]) = {
      seg.uncons match {
        case Left(r) => (Chunk.concat(acc.toList), Segment.pure(r))
        case Right((chunk,rem)) =>
          chunk.size match {
            case sz if n == sz => (Chunk.concat((acc :+ chunk).toList), rem)
            case sz if n < sz => (Chunk.concat((acc :+ chunk.take(n)).toList), rem.push(chunk.drop(n)))
            case sz => go(n - chunk.size, acc :+ chunk, rem)
          }
      }
    }
    go(n, Catenable.empty, this)
  }
}

object Segment {
  private val empty_ : Segment[Nothing,Unit] = pure(())
  def empty[O]: Segment[O,Unit] = empty_

  def pure[O,R](r: R): Segment[O,R] = new Segment[O,R] {
    def stage0 = (_,_,_,_,done) => Eval.now(step(pure(r))(done(r)))
    override def toString = s"pure($r)"
  }

  def singleton[O](o: O): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0 = (_, _, emit, _, done) => Eval.now {
      var emitted = false
      step(if (emitted) empty else singleton(o)) {
        emit(o)
        done(())
        emitted = true
      }
    }
    override def toString = s"singleton($o)"
  }

  def chunk[O](os: Chunk[O]): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0 = (_, _, _, emits, done) => Eval.now {
      var emitted = false
      step(if (emitted) empty else chunk(os)) {
        emits(os)
        done(())
        emitted = true
      }
    }
    override def toString = s"chunk($os)"
  }

  def seq[O](os: Seq[O]): Segment[O,Unit] = chunk(Chunk.seq(os))

  private[fs2]
  case class Catenated[+O,+R](s: Catenable[Segment[O,R]]) extends Segment[O,R] {
    def stage0 = (depth, defer, emit, emits, done) => Eval.always {
      var res: Option[R] = None
      var ind = 0
      val staged = s.map(_.stage(depth.increment, defer, emit, emits, r => { res = Some(r); ind += 1 }).value)
      var i = staged
      def rem = if (i.isEmpty) pure(res.get) else Catenated(i.map(_.remainder))
      step(rem) {
        i.uncons match {
          case None => done(res.get)
          case Some((hd, tl)) =>
            val ind0 = ind
            hd.step()
            defer {
              if (ind == ind0) i = hd +: tl
              else i = tl
            }
        }
      }
    }
    override def toString = s"catenated(${s.toList.mkString(", ")})"
  }

  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0 = (depth, _, emit, emits, done) => {
      var s0 = s
      Eval.now { step(unfold(s0)(f)) {
        f(s0) match {
          case None => done(())
          case Some((h,t)) => emit(h); s0 = t
        }
      }}
    }
    override def toString = s"unfold($s)($f)"
  }

  def from(n: Long, by: Long = 1): Segment[Long,Nothing] = new Segment[Long,Nothing] {
    def stage0 = (_, _, _, emits, _) => {
      var m = n
      var buf = new Array[Long](32)
      Eval.now { step(from(m,by)) {
        var i = 0
        while (i < buf.length) { buf(i) = m; m += by; i += 1 }
        emits(Chunk.longs(buf))
      }}
    }
    override def toString = s"from($n, $by)"
  }

  def step[O,R](rem: => Segment[O,R])(s: => Unit): Step[O,R] =
    new Step(Eval.always(rem), () => s)

  final class Step[+O,+R](val remainder0: Eval[Segment[O,R]], val step: () => Unit) {
    final def remainder: Segment[O,R] = remainder0.value
    final def mapRemainder[O2,R2](f: Segment[O,R] => Segment[O2,R2]): Step[O2,R2] =
      new Step(remainder0 map f, step)
    override def toString = "Step$" + ##
  }

  private val MaxFusionDepth: Depth = Depth(50)

  private def steps(t: Step[Any,Any], tailcalls: java.util.LinkedList[() => Unit]): Unit = {
    t.step()
    while (!tailcalls.isEmpty()) {
      val tc = tailcalls.remove()
      tc()
    }
  }

  private def makeTrampoline = new java.util.LinkedList[() => Unit]()
  private def defer(t: java.util.LinkedList[() => Unit]): (=>Unit) => Unit =
    u => t.addLast(() => u)

  final case class Depth(value: Int) extends AnyVal {
    def increment: Depth = Depth(value + 1)
    def <(that: Depth): Boolean = value < that.value
  }
}
