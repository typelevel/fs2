package fs2

import java.util.{ LinkedList => JLinkedList }
import cats.Eval

import Segment._

/**
 * Potentially infinite, pure sequence of values of type `O` and a result of type `R`.
 *
 * All methods which return a `Segment` support fusion with other arbitrary methods that
 * return `Segment`s. This is similar to the staging approach described in
 * [[https://arxiv.org/pdf/1612.06668v1.pdf Stream Fusion, to Completeness]], but without
 * code generation in staging.
 *
 * Stack safety is ensured by tracking a fusion depth. If the depth reaches the
 * limit, the computation is trampolined using `cats.Eval`.
 *
 * The [[Chunk]] type is a subtype of [[Segment]] that supports efficient index-based
 * random access.
 *
 * Implementation notes:
 *  - Some operators ask for a segment remainder from within a callback (e.g., `emits`). As such,
 *    segments should update state before invoking callbacks so that remainders can be computed
 *    accurately.
 */
abstract class Segment[+O,+R] { self =>
  private[fs2]
  def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: R => Unit): Eval[Step[O,R]]

  private[fs2]
  final def stage(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: R => Unit): Eval[Step[O,R]] =
    if (depth < MaxFusionDepth) stage0(depth.increment, defer, emit, emits, done)
    else Eval.defer {
      stage0(Depth(0), defer,
             o => defer(emit(o)),
             os => defer(emits(os)),
             r => defer(done(r)))
    }

  /**
   * Concatenates this segment with `s2`.
   *
   * @example {{{
   * scala> (Segment(1,2,3) ++ Segment(4,5,6)).toVector
   * res0: Vector[Int] = Vector(1, 2, 3, 4, 5, 6)
   * }}}
   */
  final def ++[O2>:O,R2>:R](s2: Segment[O2,R2]): Segment[O2,R2] =
    s2 match {
      case c2: Chunk[O2] if c2.isEmpty => this
      case _ => this match {
        case c: Chunk[O2] if c.isEmpty => s2
        case Catenated(s1s) => s2 match {
          case Catenated(s2s) => Catenated(s1s ++ s2s)
          case _ => Catenated(s1s :+ s2)
        }
        case s1 => s2 match {
          case Catenated(s2s) => Catenated(s1 +: s2s)
          case s2 => Catenated(Catenable(s1,s2))
        }
      }
    }

  /**
   * Like `++` but allows the result type of `s2` to differ from `R`.
   */
  final def append[O2>:O,R2](s2: Segment[O2,R2]): Segment[O2,(R,R2)] =
    self.flatMapResult(r => s2.mapResult(r -> _))

  /** Alias for `mapResult( => r2)`. */
  final def asResult[R2](r2: R2): Segment[O,R2] = mapResult(_ => r2)

  /**
   * Filters and maps simultaneously.
   *
   * @example {{{
   * scala> Segment(Some(1), None, Some(2)).collect { case Some(i) => i }.toVector
   * res0: Vector[Int] = Vector(1, 2)
   * }}}
   */
  final def collect[O2](pf: PartialFunction[O,O2]): Segment[O2,R] = new Segment[O2,R] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: R => Unit) = Eval.defer {
      self.stage(depth.increment, defer,
        o => if (pf.isDefinedAt(o)) emit(pf(o)) else emits(Chunk.empty),
        os => {
          var i = 0
          var filtered = false
          var emitted = false
          while (i < os.size) {
            val o = os(i)
            if (pf.isDefinedAt(o)) {
              emit(pf(o))
              emitted = true
            } else filtered = true
            i += 1
          }
          if (os.isEmpty || (filtered && !emitted)) emits(Chunk.empty)
        },
        done).map(_.mapRemainder(_ collect pf))
    }
    override def toString = s"($self).collect(<pf1>)"
  }

  /**
   * Equivalent to `Segment.singleton(o2) ++ this`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).toVector
   * res0: Vector[Int] = Vector(0, 1, 2, 3)
   * }}}
   */
  final def cons[O2>:O](o2: O2): Segment[O2,R] =
    prepend(Segment.singleton(o2))

  /**
   * Returns a segment that suppresses all output and returns the result of this segment when run.
   *
   * @example {{{
   * scala> Segment.from(0).take(3).drain.run.toOption.get.take(5).toVector
   * res0: Vector[Long] = Vector(3, 4, 5, 6, 7)
   * }}}
   */
  final def drain: Segment[Nothing,R] = new Segment[Nothing,R] {
    def stage0(depth: Depth, defer: Defer, emit: Nothing => Unit, emits: Chunk[Nothing] => Unit, done: R => Unit) = Eval.defer {
      var sinceLastEmit = 0
      def maybeEmitEmpty() = {
        sinceLastEmit += 1
        if (sinceLastEmit >= 128) {
          sinceLastEmit = 0
          emits(Chunk.empty)
        }
      }
      self.stage(depth.increment, defer, o => maybeEmitEmpty(), os => maybeEmitEmpty(), done).map(_.mapRemainder(_.drain))
    }
    override def toString = s"($self).drain"
  }

  /**
   * Eagerly drops `n` elements from the head of this segment, returning either the result and the
   * number of elements remaining to drop, if the end of the segment was reached, or a new segment,
   * if the end of the segment was not reached.
   *
   * @example {{{
   * scala> Segment(1,2,3,4,5).drop(3).toOption.get.toVector
   * res0: Vector[Int] = Vector(4, 5)
   * scala> Segment(1,2,3,4,5).drop(7)
   * res1: Either[(Unit, Long),Segment[Int,Unit]] = Left(((),2))
   * }}}
   */
  final def drop(n: Long): Either[(R,Long),Segment[O,R]] = {
    var rem = n
    var leftovers: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[R] = None
    val trampoline = new Trampoline
    val step = stage(Depth(0), trampoline.defer,
      o => if (rem > 0) rem -= 1 else leftovers = leftovers :+ Chunk.singleton(o),
      os => if (rem > os.size) rem -= os.size else {
        var i = rem.toInt
        while (i < os.size) {
          leftovers = leftovers :+ Chunk.singleton(os(i))
          i += 1
        }
        rem = 0
      },
      r => { result = Some(r); throw Done }).value
    try while (rem > 0 && result.isEmpty) stepAll(step, trampoline)
    catch { case Done => }
    result match {
      case None => Right(if (leftovers.isEmpty) step.remainder else step.remainder.prepend(Segment.catenated(leftovers)))
      case Some(r) => if (leftovers.isEmpty) Left((r,rem)) else Right(Segment.pure(r).prepend(Segment.catenated(leftovers)))
    }
  }

  /**
   * Eagerly drops elements from the head of this segment until the supplied predicate returns false,
   * returning either the result, if the end of the segment was reached without the predicate failing,
   * or the remaining segment.
   *
   * If `dropFailure` is true, the first element that failed the predicate will be dropped. If false,
   * the first element that failed the predicate will be the first element of the remainder.
   *
   * @example {{{
   * scala> Segment(1,2,3,4,5).dropWhile(_ < 3).map(_.toVector)
   * res0: Either[Unit,Vector[Int]] = Right(Vector(3, 4, 5))
   * scala> Segment(1,2,3,4,5).dropWhile(_ < 10)
   * res1: Either[Unit,Segment[Int,Unit]] = Left(())
   * }}}
   */
  final def dropWhile(p: O => Boolean, dropFailure: Boolean = false): Either[R,Segment[O,R]] = {
    var dropping = true
    var leftovers: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[R] = None
    val trampoline = new Trampoline
    val step = stage(Depth(0), trampoline.defer,
      o => { if (dropping) { dropping = p(o); if (!dropping && !dropFailure) leftovers = leftovers :+ Chunk.singleton(o) } },
      os => {
        var i = 0
        while (dropping && i < os.size) {
          dropping = p(os(i))
          i += 1
        }
        if (!dropping) {
          var j = i - 1
          if (dropFailure) j += 1
          if (j == 0) leftovers = leftovers :+ os
          else while (j < os.size) {
            leftovers = leftovers :+ Chunk.singleton(os(j))
            j += 1
          }
        }
      },
      r => { result = Some(r); throw Done }).value
    try while (dropping && result.isEmpty) stepAll(step, trampoline)
    catch { case Done => }
    result match {
      case None => Right(if (leftovers.isEmpty) step.remainder else step.remainder.prepend(Segment.catenated(leftovers)))
      case Some(r) => if (leftovers.isEmpty && dropping) Left(r) else Right(Segment.pure(r).prepend(Segment.catenated(leftovers)))
    }
  }

  /**
   * Filters output elements of this segment with the supplied predicate.
   *
   * @example {{{
   * scala> Segment(1,2,3,4,5).filter(_ % 2 == 0).toVector
   * res0: Vector[Int] = Vector(2, 4)
   * }}}
   */
  final def filter[O2](p: O => Boolean): Segment[O,R] = new Segment[O,R] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: R => Unit) = Eval.defer {
      self.stage(depth.increment, defer,
        o => if (p(o)) emit(o) else emits(Chunk.empty),
        os => {
          var i = 0
          var filtered = false
          var emitted = false
          while (i < os.size) {
            val o = os(i)
            if (p(o)) {
              emit(o)
              emitted = true
            } else filtered = true
            i += 1
          }
          if (os.isEmpty || (filtered && !emitted)) emits(Chunk.empty)
        },
        done).map(_.mapRemainder(_ filter p))
    }
    override def toString = s"($self).filter(<pf1>)"
  }

  /**
   * List-like `flatMap`, which applies `f` to each element of the segment and concatenates
   * the results.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).flatMap(i => Segment.seq(List.fill(i)(i))).toVector
   * res0: Vector[Int] = Vector(1, 2, 2, 3, 3, 3)
   * }}}
   */
  final def flatMap[O2,R2](f: O => Segment[O2,R2]): Segment[O2,(R,Option[R2])] = new Segment[O2,(R,Option[R2])] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: ((R,Option[R2])) => Unit) = Eval.defer {
      val q = new collection.mutable.Queue[O]()
      var inner: Step[O2,R2] = null
      var outerResult: Option[R] = None
      var lastInnerResult: Option[R2] = None
      val outerStep = self.stage(depth.increment, defer,
        o => q += o,
        os => os.foreach { o => q += o },
        r => outerResult = Some(r))

      outerStep.map { outer =>
        step {
          outerResult match {
            case Some(r) =>
              if (q.isEmpty) {
                if (inner eq null) Segment.empty.asResult(r -> None)
                else inner.remainder.mapResult(r2 => r -> Some(r2))
              } else {
                val s = Chunk.indexedSeq(q.toIndexedSeq).asResult(r).flatMap(f)
                if (inner eq null) s else s.prepend(inner.remainder)
              }
            case None =>
              val s = outer.remainder.prepend(Chunk.indexedSeq(q.toIndexedSeq)).flatMap(f)
              if (inner eq null) s else s.prepend(inner.remainder)
          }
        } {
          if (inner eq null) {
            if (q.nonEmpty) {
              inner = f(q.dequeue).stage(depth.increment, defer, emit, emits, r => { inner = null; lastInnerResult = Some(r) }).value
            } else {
              if (outerResult.isDefined) done(outerResult.get -> lastInnerResult)
              else outer.step()
            }
          } else inner.step()
        }
      }
    }
    override def toString = s"($self).flatMap(<f1>)"
  }

  /**
   * Stateful version of `flatMap`, where the function depends on a state value initialized to
   * `init` and updated upon each output.
   *
   * The final state is returned in the result, paired with the result of the source stream.
   *
   * @example {{{
   * scala> val src = Segment("Hello", "World", "\n", "From", "Mars").flatMapAccumulate(0)((l,s) =>
   *      |   if (s == "\n") Segment.empty.asResult(0) else Segment((l,s)).asResult(l + s.length))
   * scala> src.toVector
   * res0: Vector[(Int,String)] = Vector((0,Hello), (5,World), (0,From), (4,Mars))
   * scala> src.drain.run
   * res1: (Unit,Int) = ((),8)
   * }}}
   */
  final def flatMapAccumulate[S,O2](init: S)(f: (S,O) => Segment[O2,S]): Segment[O2,(R,S)] = new Segment[O2,(R,S)] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: ((R,S)) => Unit) = Eval.defer {
      var state: S = init
      val q = new collection.mutable.Queue[O]()
      var inner: Step[O2,S] = null
      var outerResult: Option[R] = None
      val outerStep = self.stage(depth.increment, defer,
        o => q += o,
        os => os.foreach(o => q += o),
        r => outerResult = Some(r))

      outerStep.map { outer =>
        step {
          val innerRem = if (inner eq null) Segment.empty.asResult(state) else inner.remainder
          outerResult match {
            case Some(r) =>
              if (q.isEmpty) innerRem.mapResult(s => r -> s)
              else Chunk.seq(q.toIndexedSeq).asResult(r).flatMapAccumulate(state)(f).prepend(innerRem)
            case None => outer.remainder.prepend(Chunk.seq(q.toIndexedSeq)).flatMapAccumulate(state)(f).prepend(innerRem)
          }
        } {
          if (inner eq null) {
            if (q.nonEmpty) {
              val next = q.dequeue
              val innerSegment = f(state, next)
              inner = innerSegment.stage(depth.increment, defer, emit, emits, r => { inner = null; state = r }).value
            } else {
              if (outerResult.isDefined) done(outerResult.get -> state)
              else outer.step()
            }
          } else inner.step()
        }
      }
    }
    override def toString = s"($self).flatMapAccumulate($init)(<f2>)"
  }

  /**
    * Like `append` but allows to use result to continue the segment.
    */
  final def flatMapResult[O2>:O,R2](f: R => Segment[O2,R2]): Segment[O2,R2] = new Segment[O2,R2] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: R2 => Unit) = Eval.always {
      var res1: Option[Step[O2,R2]] = None
      var res2: Option[R2] = None
      val staged = self.stage(depth.increment, defer, emit, emits,
        r => res1 = Some(f(r).stage(depth.increment, defer, emit, emits, r => res2 = Some(r)).value)).value

      step(if (res1.isEmpty) staged.remainder.flatMapResult(f) else res1.get.remainder) {
        if (res1.isEmpty) staged.step()
        else if (res2.isEmpty) res1.get.step()
        else done(res2.get)
      }
    }
    override def toString = s"flatMapResult($self, <f>)"
  }

  /**
   * Flattens a `Segment[Segment[O2,R],R]` in to a `Segment[O2,R]`.
   *
   * @example {{{
   * scala> Segment(Segment(1, 2), Segment(3, 4, 5)).flatten.toVector
   * res0: Vector[Int] = Vector(1, 2, 3, 4, 5)
   * }}}
   */
  final def flatten[O2,R2 >: R](implicit ev: O <:< Segment[O2,R2]): Segment[O2,R2] = {
    val _ = ev
    this.asInstanceOf[Segment[Segment[O2,R2],R2]].flatMap(identity).mapResult(_._1)
  }

  /**
   * Flattens a `Segment[Chunk[O2],R]` in to a `Segment[O2,R]`.
   *
   * @example {{{
   * scala> Segment(Chunk(1, 2), Chunk(3, 4, 5)).flattenChunks.toVector
   * res0: Vector[Int] = Vector(1, 2, 3, 4, 5)
   * }}}
   */
  final def flattenChunks[O2](implicit ev: O <:< Chunk[O2]): Segment[O2,R] = {
    val _ = ev
    this.asInstanceOf[Segment[Chunk[O2],R]].mapConcat(identity)
  }

  /**
   * Folds the output elements of this segment and returns the result as the result of the returned segment.
   *
   * @example {{{
   * scala> Segment(1,2,3,4,5).fold(0)(_ + _).run
   * res0: Int = 15
   * }}}
   */
  final def fold[B](z: B)(f: (B,O) => B): Segment[Nothing,B] = new Segment[Nothing,B] {
    def stage0(depth: Depth, defer: Defer, emit: Nothing => Unit, emits: Chunk[Nothing] => Unit, done: B => Unit) = {
      var b = z
      self.stage(depth.increment, defer,
        o => b = f(b, o),
        os => { var i = 0; while (i < os.size) { b = f(b, os(i)); i += 1 } },
        r => done(b)).map(_.mapRemainder(_.fold(b)(f)))
    }
    override def toString = s"($self).fold($z)(<f1>)"
  }

  private[fs2] final def foldRightLazy[B](z: B)(f: (O,=>B) => B): B = {
    unconsChunks match {
      case Right((hds,tl)) =>
        def loopOnChunks(hds: Catenable[Chunk[O]]): B = {
          hds.uncons match {
            case Some((hd,hds)) =>
              val sz = hd.size
              if (sz == 1) f(hd(0), loopOnChunks(hds))
              else {
                def loopOnElements(idx: Int): B = {
                  if (idx < sz) f(hd(idx), loopOnElements(idx + 1))
                  else loopOnChunks(hds)
                }
                loopOnElements(0)
              }
            case None => tl.foldRightLazy(z)(f)
          }
        }
        loopOnChunks(hds)

      case Left(_) => z
    }
  }

  /**
   * Invokes `f` on each chunk of this segment.
   *
   * @example {{{
   * scala> val buf = collection.mutable.ListBuffer[Chunk[Int]]()
   * scala> Segment(1,2,3).cons(0).foreachChunk(c => buf += c)
   * res0: Unit = ()
   * scala> buf.toList
   * res1: List[Chunk[Int]] = List(Chunk(0), Chunk(1, 2, 3))
   * }}}
   */
  def foreachChunk(f: Chunk[O] => Unit): Unit = {
    val trampoline = new Trampoline
    val step = stage(Depth(0), trampoline.defer, o => f(Chunk.singleton(o)), f, r => throw Done).value
    try while (true) stepAll(step, trampoline)
    catch { case Done => }
  }

  /**
   * Invokes `f` on each output of this segment.
   *
   * @example {{{
   * scala> val buf = collection.mutable.ListBuffer[Int]()
   * scala> Segment(1,2,3).cons(0).foreach(i => buf += i)
   * res0: Unit = ()
   * scala> buf.toList
   * res1: List[Int] = List(0, 1, 2, 3)
   * }}}
   */
  def foreach(f: O => Unit): Unit = {
    foreachChunk { c =>
      var i = 0
      while (i < c.size) {
        f(c(i))
        i += 1
      }
    }
  }

  /**
   * Returns a segment that suppresses all output and returns the last element output by
   * source segment paired with the source segment result.
   *
   * @example {{{
   * scala> Segment(1,2,3).last.run
   * res0: (Unit, Option[Int]) = ((),Some(3))
   * }}}
   */
  def last: Segment[Nothing,(R,Option[O])] = new Segment[Nothing,(R,Option[O])] {
    def stage0(depth: Depth, defer: Defer, emit: Nothing => Unit, emits: Chunk[Nothing] => Unit, done: ((R,Option[O])) => Unit) = Eval.defer {
      var last: Option[O] = None
      self.stage(depth.increment, defer,
        o => last = Some(o),
        os => last = Some(os(os.size - 1)),
        r => done((r,last))
      ).map(_.mapRemainder(_.last))
    }
    override def toString = s"($self).last"
  }

  /**
   * Returns a segment that maps each output using the supplied function.
   *
   * @example {{{
   * scala> Segment(1,2,3).map(_ + 1).toVector
   * res0: Vector[Int] = Vector(2, 3, 4)
   * }}}
   */
  def map[O2](f: O => O2): Segment[O2,R] = new Segment[O2,R] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: R => Unit) = Eval.defer {
      self.stage(depth.increment, defer,
        o => emit(f(o)),
        os => { var i = 0; while (i < os.size) { emit(f(os(i))); i += 1; } },
        done).map(_.mapRemainder(_ map f))
    }
    override def toString = s"($self).map(<f1>)"
  }

  /**
   * Stateful version of map, where the map function depends on a state value initialized to
   * `init` and updated upon each output value.
   *
   * The final state is returned in the result, paired with the result of the source stream.
   *
   * @example {{{
   * scala> val src = Segment("Hello", "World").mapAccumulate(0)((l,s) => (l + s.length, (l, s)))
   * scala> src.toVector
   * res0: Vector[(Int,String)] = Vector((0,Hello), (5,World))
   * scala> src.drain.run
   * res1: (Unit,Int) = ((),10)
   * }}}
   */
  def mapAccumulate[S,O2](init: S)(f: (S,O) => (S,O2)): Segment[O2,(R,S)] = new Segment[O2,(R,S)] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: ((R,S)) => Unit) = Eval.defer {
      var s = init
      def doEmit(o: O) = {
        val (newS,o2) = f(s,o)
        s = newS
        emit(o2)
      }
      self.stage(depth.increment, defer,
        o => doEmit(o),
        os => { var i = 0; while (i < os.size) { doEmit(os(i)); i += 1; } },
        r => done(r -> s)
      ).map(_.mapRemainder(_.mapAccumulate(s)(f)))
    }
    override def toString = s"($self).mapAccumulate($init)(<f1>)"
  }

  /**
   * Returns a segment that maps each output using the supplied function and concatenates all the results.
   *
   * @example {{{
   * scala> Segment(1,2,3).mapConcat(o => Chunk.seq(List.range(0, o))).toVector
   * res0: Vector[Int] = Vector(0, 0, 1, 0, 1, 2)
   * }}}
   */
  final def mapConcat[O2](f: O => Chunk[O2]): Segment[O2,R] = new Segment[O2,R] {
    def stage0(depth: Depth, defer: Defer, emit: O2 => Unit, emits: Chunk[O2] => Unit, done: R => Unit) = Eval.defer {
      self.stage(depth.increment, defer,
        o => emits(f(o)),
        os => { var i = 0; while (i < os.size) { emits(f(os(i))); i += 1; } },
        done).map(_.mapRemainder(_ mapConcat f))
    }
    override def toString = s"($self).mapConcat(<f1>)"
  }

  /**
   * Maps the supplied function over the result of this segment.
   *
   * @example {{{
   * scala> Segment('a', 'b', 'c').withSize.mapResult { case (_, size) => size }.drain.run
   * res0: Long = 3
   * }}}
   */
  final def mapResult[R2](f: R => R2): Segment[O,R2] = new Segment[O,R2] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: R2 => Unit) = Eval.defer {
      self.stage(depth.increment, defer, emit, emits, r => done(f(r))).map(_.mapRemainder(_ mapResult f))
    }
    override def toString = s"($self).mapResult(<f1>)"
  }

  /**
   * Equivalent to `s2 ++ this`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).prepend(Segment(-1, 0)).toVector
   * res0: Vector[Int] = Vector(-1, 0, 1, 2, 3)
   * }}}
   */
  final def prepend[O2>:O](c: Segment[O2,Any]): Segment[O2,R] = {
    // note - cast is fine, as `this` is guaranteed to provide an `R`,
    // overriding the `Any` produced by `c`
    c.asInstanceOf[Segment[O2,R]] ++ this
  }

  /**
   * Computes the result of this segment. May only be called when `O` is `Nothing`, to prevent accidentally ignoring
   * output values. To intentionally ignore outputs, call `s.drain.run`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).withSize.drain.run
   * res0: (Unit,Long) = ((),3)
   * }}}
   */
  final def run(implicit ev: O <:< Nothing): R = {
    val _ = ev // Convince scalac that ev is used
    var result: Option[R] = None
    val trampoline = new Trampoline
    val step = stage(Depth(0), trampoline.defer, _ => (), _ => (), r => { result = Some(r); throw Done }).value
    try while (true) stepAll(step, trampoline)
    catch { case Done => }
    result.get
  }

  /**
   * Like fold but outputs intermediate results. If `emitFinal` is true, upon reaching the end of the stream, the accumulated
   * value is output. If `emitFinal` is false, the accumulated output is not output. Regardless, the accumulated value is
   * returned as the result of the segment.
   *
   * @example {{{
   * scala> Segment(1, 2, 3, 4, 5).scan(0)(_+_).toVector
   * res0: Vector[Int] = Vector(0, 1, 3, 6, 10, 15)
   * }}}
   */
  final def scan[B](z: B, emitFinal: Boolean = true)(f: (B,O) => B): Segment[B,B] = new Segment[B,B] {
    def stage0(depth: Depth, defer: Defer, emit: B => Unit, emits: Chunk[B] => Unit, done: B => Unit) = {
      var b = z
      self.stage(depth.increment, defer,
        o => { emit(b); b = f(b, o) },
        os => { var i = 0; while (i < os.size) { emit(b); b = f(b, os(i)); i += 1 } },
        r => { if (emitFinal) emit(b); done(b) }).map(_.mapRemainder(_.scan(b)(f)))
    }
    override def toString = s"($self).scan($z)($f)"
  }

  /**
   * Splits this segment at the specified index by simultaneously taking and dropping.
   *
   * If the segment has less than `n` elements, a left is returned, providing the result of the segment,
   * all sub-segments taken, and the remaining number of elements (i.e., size - n).
   *
   * If the segment has more than `n` elements, a right is returned, providing the sub-segments up to
   * the `n`-th element and a remainder segment.
   *
   * The prefix is computed eagerly while the suffix is computed lazily.
   *
   * @example {{{
   * scala> Segment(1, 2, 3, 4, 5).splitAt(2)
   * res0: Either[(Unit,Catenable[Segment[Int,Unit]],Long),(Catenable[Segment[Int,Unit]],Segment[Int,Unit])] = Right((Catenable(Chunk(1, 2)),Chunk(3, 4, 5)))
   * scala> Segment(1, 2, 3, 4, 5).splitAt(7)
   * res0: Either[(Unit,Catenable[Segment[Int,Unit]],Long),(Catenable[Segment[Int,Unit]],Segment[Int,Unit])] = Left(((),Catenable(Chunk(1, 2, 3, 4, 5)),2))
   * }}}
   */
   final def splitAt(n: Long): Either[(R,Catenable[Segment[O,Unit]],Long),(Catenable[Segment[O,Unit]],Segment[O,R])] = {
    if (n <= 0) Right((Catenable.empty, this))
    else {
      var out: Catenable[Chunk[O]] = Catenable.empty
      var result: Option[Either[R,Segment[O,Unit]]] = None
      var rem = n
      val emits: Chunk[O] => Unit = os => {
        if (result.isDefined) {
          result = result.map(_.map(_ ++ os))
        } else if (os.nonEmpty) {
          if (os.size <= rem) {
            out = out :+ os
            rem -= os.size
          } else  {
            val (before, after) = os.strict.splitAt(rem.toInt) // nb: toInt is safe b/c os.size is an Int and rem < os.size
            out = out :+ before
            result = Some(Right(after))
            rem = 0
          }
        }
      }
      val trampoline = new Trampoline
      val step = stage(Depth(0),
        trampoline.defer,
        o => emits(Chunk.singleton(o)),
        os => emits(os),
        r => { if (result.isEmpty) result = Some(Left(r)); throw Done }).value
      try while (result.isEmpty) stepAll(step, trampoline)
      catch { case Done => }
      result.map {
        case Left(r) => Left((r,out,rem))
        case Right(after) => Right((out,step.remainder.prepend(after)))
      }.getOrElse(Right((out, step.remainder)))
    }
  }

  /**
   * Splits this segment at the first element where the supplied predicate returns false.
   *
   * Analagous to siumultaneously running `takeWhile` and `dropWhile`.
   *
   * If `emitFailure` is false, the first element which fails the predicate is returned in the suffix segment. If true,
   * it is returned as the last element in the prefix segment.
   *
   * If the end of the segment is reached and the predicate has not failed, a left is returned, providing the segment result
   * and the catenated sub-segments. Otherwise, a right is returned, providing the prefix sub-segments and the suffix remainder.
   *
   * @example {{{
   * scala> Segment(1, 2, 3, 4, 5).splitWhile(_ != 3)
   * res0: Either[(Unit,Catenable[Segment[Int,Unit]]),(Catenable[Segment[Int,Unit]],Segment[Int,Unit])] = Right((Catenable(Chunk(1, 2)),Chunk(3, 4, 5)))
   * scala> Segment(1, 2, 3, 4, 5).splitWhile(_ != 7)
   * res0: Either[(Unit,Catenable[Segment[Int,Unit]]),(Catenable[Segment[Int,Unit]],Segment[Int,Unit])] = Left(((),Catenable(Chunk(1, 2, 3, 4, 5))))
   * }}}
   */
  final def splitWhile(p: O => Boolean, emitFailure: Boolean = false): Either[(R,Catenable[Segment[O,Unit]]),(Catenable[Segment[O,Unit]],Segment[O,R])] = {
    var out: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[Either[R,Segment[O,Unit]]] = None
    var ok = true
    val emits: Chunk[O] => Unit = os => {
      if (result.isDefined) {
        result = result.map(_.map(_ ++ os))
      } else {
        var i = 0
        var j = 0
        while (ok && i < os.size) {
          ok = ok && p(os(i))
          if (!ok) j = i
          i += 1
        }
        if (ok) out = out :+ os
        else {
          val (before, after) = os.strict.splitAt(if (emitFailure) j + 1 else j)
          out = out :+ before
          result = Some(Right(after))
        }
      }
    }
    val trampoline = new Trampoline
    val step = stage(Depth(0),
      trampoline.defer,
      o => emits(Chunk.singleton(o)),
      os => emits(os),
      r => { if (result.isEmpty) result = Some(Left(r)); throw Done }).value
    try while (result.isEmpty) stepAll(step, trampoline)
    catch { case Done => }
    result.map {
      case Left(r) => Left((r,out))
      case Right(after) => Right((out,step.remainder.prepend(after)))
    }.getOrElse(Right((out, step.remainder)))
  }

  /**
   * Sums the elements of this segment and returns the sum as the segment result.
   *
   * @example {{{
   * scala> Segment(1, 2, 3, 4, 5).sum.run
   * res0: Int = 15
   * }}}
   */
  final def sum[N>:O](implicit N: Numeric[N]): Segment[Nothing,N] = sum_(N.zero)

  private def sum_[N>:O](initial: N)(implicit N: Numeric[N]): Segment[Nothing,N] = new Segment[Nothing,N] {
    def stage0(depth: Depth, defer: Defer, emit: Nothing => Unit, emits: Chunk[Nothing] => Unit, done: N => Unit) = {
      var b = initial
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
        r => done(b)).map(_.mapRemainder(_.sum_(b)))
    }
    override def toString = s"($self).sum($initial)"
  }

  /**
   * Lazily takes `n` elements from this segment. The result of the returned segment is either a left
   * containing the result of the original segment and the number of elements remaining to take when
   * the end of the source segment was reached, or a right containing the remainder of the source
   * segment after `n` elements are taken.
   *
   * @example {{{
   * scala> Segment.from(0).take(3).toVector
   * res0: Vector[Long] = Vector(0, 1, 2)
   * scala> Segment.from(0).take(3).drain.run.toOption.get.take(5).toVector
   * res1: Vector[Long] = Vector(3, 4, 5, 6, 7)
   * scala> Segment(1, 2, 3).take(5).drain.run
   * res2: Either[(Unit, Long),Segment[Int,Unit]] = Left(((),2))
   * }}}
   */
  final def take(n: Long): Segment[O,Either[(R,Long),Segment[O,R]]] =
    if (n <= 0) Segment.pure(Right(this))
    else new Segment[O,Either[(R,Long),Segment[O,R]]] {
      def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: Either[(R,Long),Segment[O,R]] => Unit) = Eval.later {
        var rem = n
        var staged: Step[O,R] = null
        staged = self.stage(depth.increment, defer,
          o => { if (rem > 0) { rem -= 1; emit(o) } else done(Right(staged.remainder.cons(o))) },
          os => { if (os.size <= rem) { rem -= os.size; emits(os) }
                  else {
                    var i = 0
                    while (rem > 0) { rem -= 1; emit(os(i)); i += 1 }
                    done(Right(staged.remainder.prepend(os.strict.drop(i))))
                  }
                },
          r => done(Left(r -> rem))
        ).value
        staged.mapRemainder(_.take(rem))
      }
      override def toString = s"($self).take($n)"
    }

  /**
   * Returns a segment that outputs elements while `p` is true.
   *
   * The result of the returned segment is either the result of the original stream, if the end
   * was reached and the predicate was still passing, or the remaining stream, if the predicate failed.
   * If `takeFailure` is true, the last element output is the first element which failed the predicate.
   * If `takeFailure` is false, the first element of the remainder is the first element which failed
   * the predicate.
   *
   * @example {{{
   * scala> Segment.from(0).takeWhile(_ < 3).toVector
   * res0: Vector[Long] = Vector(0, 1, 2)
   * scala> Segment.from(0).takeWhile(_ < 3, takeFailure = true).toVector
   * res1: Vector[Long] = Vector(0, 1, 2, 3)
   * scala> Segment.from(0).takeWhile(_ < 3).drain.run.toOption.get.take(5).toVector
   * res2: Vector[Long] = Vector(3, 4, 5, 6, 7)
   * }}}
   */
  final def takeWhile(p: O => Boolean, takeFailure: Boolean = false): Segment[O,Either[R,Segment[O,R]]] = new Segment[O,Either[R,Segment[O,R]]] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: Either[R,Segment[O,R]] => Unit) = Eval.later {
      var ok = true
      var staged: Step[O,R] = null
      staged = self.stage(depth.increment, defer,
        o => { if (ok) { ok = ok && p(o); if (ok || takeFailure) emit(o) else done(Right(staged.remainder.cons(o))) } },
        os => {
          var i = 0
          while (ok && i < os.size) {
            val o = os(i)
            ok = p(o)
            if (!ok) {
              var j = 0
              if (takeFailure) i += 1
              while (j < i) { emit(os(j)); j += 1 }
            }
            i += 1
          }
          if (ok) emits(os) else done(Right(
            if (i == 0) staged.remainder else staged.remainder.prepend(os.strict.drop(i - 1))))
        },
        r => if (ok) done(Left(r)) else done(Right(staged.remainder))
      ).value
      staged.mapRemainder(rem => if (ok) rem.takeWhile(p, takeFailure) else rem.mapResult(Left(_)))
    }
    override def toString = s"($self).takeWhile(<f1>)"
  }

  /**
   * Converts this segment to an array, discarding the result.
   *
   * Caution: calling `toArray` on an infinite sequence will not terminate.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).cons(-1).toArray
   * res0: Array[Int] = Array(-1, 0, 1, 2, 3)
   * }}}
   */
  def toArray[O2 >: O](implicit ct: reflect.ClassTag[O2]): Array[O2] = {
    val bldr = collection.mutable.ArrayBuilder.make[O2]
    foreachChunk { c =>
      var i = 0
      while (i < c.size) {
        bldr += c(i)
        i += 1
      }
    }
    bldr.result
  }
  /**
   * Converts this segment to a catenable of output values, discarding the result.
   *
   * Caution: calling `toCatenable` on an infinite sequence will not terminate.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).cons(-1).toCatenable.toList
   * res0: List[Int] = List(-1, 0, 1, 2, 3)
   * }}}
   */
  def toCatenable: Catenable[O] = {
    var result: Catenable[O] = Catenable.empty
    foreach(o => result = result :+ o)
    result
  }

  /**
   * Converts this segment to a single chunk, discarding the result.
   *
   * Caution: calling `toChunk` on an infinite sequence will not terminate.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).cons(-1).toChunk
   * res0: Chunk[Int] = Chunk(-1, 0, 1, 2, 3)
   * }}}
   */
  def toChunk: Chunk[O] = Chunk.vector(toVector)

  /**
   * Converts this segment to a sequence of chunks, discarding the result.
   *
   * Caution: calling `toChunks` on an infinite sequence will not terminate.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).cons(-1).toChunks.toList
   * res0: List[Chunk[Int]] = List(Chunk(-1), Chunk(0), Chunk(1, 2, 3))
   * }}}
   */
  def toChunks: Catenable[Chunk[O]] = {
    var acc: Catenable[Chunk[O]] = Catenable.empty
    foreachChunk(c => acc = acc :+ c)
    acc
  }

  /**
   * Converts this segment to a list, discarding the result.
   *
   * Caution: calling `toList` on an infinite sequence will not terminate.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).cons(-1).toList
   * res0: List[Int] = List(-1, 0, 1, 2, 3)
   * }}}
   */
  def toList: List[O] = {
    val buf = new collection.mutable.ListBuffer[O]
    foreachChunk { c =>
      var i = 0
      while (i < c.size) {
        buf += c(i)
        i += 1
      }
    }
    buf.result
  }

  /**
   * Converts this segment to a list, discarding the result.
   *
   * Caution: calling `toList` on an infinite sequence will not terminate.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).cons(-1).toList
   * res0: List[Int] = List(-1, 0, 1, 2, 3)
   * }}}
   */
  def toVector: Vector[O] = {
    val buf = new collection.immutable.VectorBuilder[O]
    foreachChunk(c => { buf ++= c.toVector; () })
    buf.result
  }

  /**
   * Returns the first output sub-segment of this segment along with the remainder, wrapped in `Right`, or
   * if this segment is empty, returns the result wrapped in `Left`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).uncons
   * res0: Either[Unit,(Segment[Int,Unit], Segment[Int,Unit])] = Right((Chunk(0),Chunk(1, 2, 3)))
   * scala> Segment.empty[Int].uncons
   * res1: Either[Unit,(Segment[Int,Unit], Segment[Int,Unit])] = Left(())
   * }}}
   */
  final def uncons: Either[R, (Segment[O,Unit],Segment[O,R])] = unconsChunks match {
    case Left(r) => Left(r)
    case Right((cs,tl)) => Right(catenated(cs) -> tl)
  }

  /**
   * Returns the first output of this segment along with the remainder, wrapped in `Right`, or
   * if this segment is empty, returns the result wrapped in `Left`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).cons(0).uncons1
   * res0: Either[Unit,(Int, Segment[Int,Unit])] = Right((0,Chunk(1, 2, 3)))
   * scala> Segment.empty[Int].uncons1
   * res1: Either[Unit,(Int, Segment[Int,Unit])] = Left(())
   * }}}
   */
  @annotation.tailrec
  final def uncons1: Either[R, (O,Segment[O,R])] =
    unconsChunk match {
      case Right((c, tl)) =>
        val sz = c.size
        if (sz == 0) tl.uncons1
        else if (sz == 1) Right(c(0) -> tl)
        else Right(c(0) -> tl.prepend(c.strict.drop(1)))
      case Left(r) => Left(r)
    }

  /**
   * Returns the first output chunk of this segment along with the remainder, wrapped in `Right`, or
   * if this segment is empty, returns the result wrapped in `Left`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).prepend(Chunk(-1, 0)).unconsChunk
   * res0: Either[Unit,(Chunk[Int], Segment[Int,Unit])] = Right((Chunk(-1, 0),Chunk(1, 2, 3)))
   * scala> Segment.empty[Int].unconsChunk
   * res1: Either[Unit,(Chunk[Int], Segment[Int,Unit])] = Left(())
   * }}}
   */
  def unconsChunk: Either[R, (Chunk[O],Segment[O,R])] =
    unconsChunks match {
      case Left(r) => Left(r)
      case Right((cs,tl)) => Right(cs.uncons.map { case (hd,tl2) => hd -> tl.prepend(Segment.catenated(tl2)) }.get)
    }

  /**
   * Returns the first output chunks of this segment along with the remainder, wrapped in `Right`, or
   * if this segment is empty, returns the result wrapped in `Left`.
   *
   * Differs from `unconsChunk` when a single step results in multiple outputs.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).prepend(Chunk(-1, 0)).unconsChunks
   * res0: Either[Unit,(Catenable[Chunk[Int]], Segment[Int,Unit])] = Right((Catenable(Chunk(-1, 0)),Chunk(1, 2, 3)))
   * scala> Segment.empty[Int].unconsChunks
   * res1: Either[Unit,(Catenable[Chunk[Int]], Segment[Int,Unit])] = Left(())
   * }}}
   */
  final def unconsChunks: Either[R, (Catenable[Chunk[O]],Segment[O,R])] = {
    var out: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[R] = None
    var ok = true
    val trampoline = new Trampoline
    val step = stage(Depth(0),
      trampoline.defer,
      o => { out = out :+ Chunk.singleton(o); ok = false },
      os => { out = out :+ os; ok = false },
      r => { result = Some(r); throw Done }).value
    try while (ok) stepAll(step, trampoline)
    catch { case Done => }
    result match {
      case None =>
        Right(out -> step.remainder)
      case Some(r) =>
        if (out.isEmpty) Left(r)
        else Right(out -> pure(r))
    }
  }

  /**
   * Alias for `map(_ => ())`.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).void.toList
   * res0: List[Unit] = List((), (), ())
   * }}}
   */
  final def void: Segment[Unit,R] = map(_ => ())

  /**
   * Returns a new segment which discards the result and replaces it with unit.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).take(2).voidResult
   * res0: Segment[Int,Unit] = ((Chunk(1, 2, 3)).take(2)).mapResult(<f1>)
   * }}}
   */
  final def voidResult: Segment[O,Unit] = mapResult(_ => ())

  /**
   * Returns a new segment which includes the number of elements output in the result.
   *
   * @example {{{
   * scala> Segment(1, 2, 3).withSize.drain.run
   * res0: (Unit,Long) = ((),3)
   * }}}
   */
  def withSize: Segment[O,(R,Long)] = withSize_(0)

  private def withSize_(init: Long): Segment[O,(R,Long)] = new Segment[O,(R,Long)] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: ((R,Long)) => Unit) = Eval.defer {
      var length = init
      self.stage(depth.increment, defer,
        o => { length += 1; emit(o) },
        os => { length += os.size; emits(os) },
        r => done((r,length))
      ).map(_.mapRemainder(_.withSize_(length)))
    }
    override def toString = s"($self).withSize_($init)"
  }

  /**
   * Zips this segment with another segment using the supplied function to combine elements from this and that.
   * Terminates when either segment terminates.
   *
   * @example {{{
   * scala> Segment(1,2,3).zipWith(Segment(4,5,6,7))(_+_).toList
   * res0: List[Int] = List(5, 7, 9)
   * }}}
   */
  def zipWith[O2,R2,O3](that: Segment[O2,R2])(f: (O,O2) => O3): Segment[O3,Either[(R,Segment[O2,R2]),(R2,Segment[O,R])]] =
    new Segment[O3,Either[(R,Segment[O2,R2]),(R2,Segment[O,R])]] {
      def stage0(depth: Depth, defer: Defer, emit: O3 => Unit, emits: Chunk[O3] => Unit, done: Either[(R,Segment[O2,R2]),(R2,Segment[O,R])] => Unit) = Eval.defer {
        val l = new scala.collection.mutable.Queue[Chunk[O]]
        var lpos = 0
        var lStepped = false
        val r = new scala.collection.mutable.Queue[Chunk[O2]]
        var rpos = 0
        var rStepped = false
        def doZip(): Unit = {
          var lh = if (l.isEmpty) null else l.head
          var rh = if (r.isEmpty) null else r.head
          var out1: Option[O3] = None
          var out: scala.collection.immutable.VectorBuilder[O3] = null
          while ((lh ne null) && lpos < lh.size && (rh ne null) && rpos < rh.size) {
            val zipCount = (lh.size - lpos) min (rh.size - rpos)
            if (zipCount == 1 && out1 == None && (out eq null)) {
              out1 = Some(f(lh(lpos),rh(rpos)))
              lpos += 1
              rpos += 1
            } else {
              if (out eq null) {
                out = new scala.collection.immutable.VectorBuilder[O3]()
                if (out1.isDefined) {
                  out += out1.get
                  out1 = None
                }
              }
              var i = 0
              while (i < zipCount) {
                out += f(lh(lpos),rh(rpos))
                i += 1
                lpos += 1
                rpos += 1
              }
            }
            if (lpos == lh.size) {
              l.dequeue
              lh = if (l.isEmpty) null else l.head
              lpos = 0
            }
            if (rpos == rh.size) {
              r.dequeue
              rh = if (r.isEmpty) null else r.head
              rpos = 0
            }
          }
          if (out1.isDefined) emit(out1.get)
          else if (out ne null) emits(Chunk.vector(out.result))
        }
        val emitsL: Chunk[O] => Unit = os => { if (os.nonEmpty) l += os; doZip }
        val emitsR: Chunk[O2] => Unit = os => { if (os.nonEmpty) r += os; doZip }
        def unusedL: Segment[O,Unit] = if (l.isEmpty) Segment.empty else l.tail.foldLeft(if (lpos == 0) l.head else l.head.drop(lpos).toOption.get)(_ ++ _)
        def unusedR: Segment[O2,Unit] = if (r.isEmpty) Segment.empty else r.tail.foldLeft(if (rpos == 0) r.head else r.head.drop(rpos).toOption.get)(_ ++ _)
        var lResult: Option[R] = None
        var rResult: Option[R2] = None
        for {
          stepL <- self.stage(depth, defer, o => emitsL(Chunk.singleton(o)), emitsL, res => lResult = Some(res))
          stepR <- that.stage(depth, defer, o2 => emitsR(Chunk.singleton(o2)), emitsR, res => rResult = Some(res))
        } yield {
          step {
            val remL: Segment[O,R] = if (lStepped) stepL.remainder.prepend(unusedL) else self
            val remR: Segment[O2,R2] = if (rStepped) stepR.remainder.prepend(unusedR) else that
            remL.zipWith(remR)(f)
          } {
            if (lResult.isDefined && l.isEmpty) {
              done(Left(lResult.get -> stepR.remainder.prepend(unusedR)))
            } else if (rResult.isDefined && r.isEmpty) {
              done(Right(rResult.get -> stepL.remainder.prepend(unusedL)))
            } else if (lResult.isEmpty && l.isEmpty) { lStepped = true; stepL.step() }
            else { rStepped = true; stepR.step() }
          }
        }
      }
      override def toString = s"($self).zipWith($that)(<f1>)"
    }

  override def hashCode: Int = toVector.hashCode
  override def equals(a: Any): Boolean = a match {
    case s: Segment[O,R] => this.toVector == s.toVector
    case _ => false
  }
}

object Segment {

  /** Creates a segment with the specified values. */
  def apply[O](os: O*): Segment[O,Unit] = seq(os)

  /** Creates a segment backed by an array. */
  def array[O](os: Array[O]): Segment[O,Unit] = Chunk.array(os)

  /** Creates a segment backed by 0 or more other segments. */
  def catenated[O,R](os: Catenable[Segment[O,R]], ifEmpty: => R): Segment[O,R] =
    os match {
      case Catenable.Empty => Segment.pure(ifEmpty)
      case Catenable.Singleton(s) => s
      case _ => Catenated(os)
    }

  /** Creates a segment backed by 0 or more other segments. */
  def catenated[O](os: Catenable[Segment[O,Unit]]): Segment[O,Unit] =
    os match {
      case Catenable.Empty => Segment.empty
      case Catenable.Singleton(s) => s
      case _ => Catenated(os)
    }

  /** Creates an infinite segment of the specified value. */
  def constant[O](o: O): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: Unit => Unit) =
      Eval.later { step(constant(o)) { emit(o) } }
    override def toString = s"constant($o)"
  }

  /** Creates an empty segment of type `O`. */
  def empty[O]: Segment[O,Unit] = Chunk.empty

  /** Creates a segment which outputs values starting at `n` and incrementing by `by` between each value. */
  def from(n: Long, by: Long = 1): Segment[Long,Nothing] = new Segment[Long,Nothing] {
    def stage0(depth: Depth, defer: Defer, emit: Long => Unit, emits: Chunk[Long] => Unit, done: Nothing => Unit) = {
      var m = n
      val buf = new Array[Long](32)
      Eval.later { step(from(m,by)) {
        var i = 0
        while (i < buf.length) { buf(i) = m; m += by; i += 1 }
        emits(Chunk.longs(buf))
      }}
    }
    override def toString = s"from($n, $by)"
  }

  /** Creates a segment backed by an `IndexedSeq`. */
  def indexedSeq[O](os: IndexedSeq[O]): Segment[O,Unit] = Chunk.indexedSeq(os)

  /** Creates a segment which outputs no values and returns `r`. */
  def pure[O,R](r: R): Segment[O,R] = new Segment[O,R] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: R => Unit) =
      Eval.later(step(pure(r))(done(r)))
    override def toString = s"pure($r)"
  }

  /** Creates a segment which outputs a single value `o`. */
  def singleton[O](o: O): Segment[O,Unit] = Chunk.singleton(o)

  def step[O,R](rem: => Segment[O,R])(s: => Unit): Step[O,R] =
    new Step(Eval.always(rem), () => s)

  final class Step[+O,+R](val remainder0: Eval[Segment[O,R]], val step: () => Unit) {
    final def remainder: Segment[O,R] = remainder0.value
    final def mapRemainder[O2,R2](f: Segment[O,R] => Segment[O2,R2]): Step[O2,R2] =
      new Step(remainder0 map f, step)
    override def toString = "Step$" + ##
  }

  /** Creates a segment backed by a `Seq`. */
  def seq[O](os: Seq[O]): Segment[O,Unit] = Chunk.seq(os)

  /**
   * Creates a segment by successively applying `f` until a `None` is returned, emitting
   * each output `O` and using each output `S` as input to the next invocation of `f`.
   */
  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: Unit => Unit) = {
      var s0 = s
      Eval.later { step(unfold(s0)(f)) {
        f(s0) match {
          case None => done(())
          case Some((h,t)) => s0 = t; emit(h)
        }
      }}
    }
    override def toString = s"unfold($s)(<f1>)"
  }

  /**
   * Creates a segment by successively applying `f` until a `None` is returned, emitting
   * each output chunk and using each output `S` as input to the next invocation of `f`.
   */
  def unfoldChunk[S,O](s: S)(f: S => Option[(Chunk[O],S)]): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: Unit => Unit) = {
      var s0 = s
      Eval.later { step(unfoldChunk(s0)(f)) {
        f(s0) match {
          case None => done(())
          case Some((c,t)) => s0 = t; emits(c)
        }
      }}
    }
    override def toString = s"unfoldSegment($s)(<f1>)"
  }
  /** Creates a segment backed by a `Vector`. */
  def vector[O](os: Vector[O]): Segment[O,Unit] = Chunk.vector(os)

  private[fs2] case class Catenated[+O,+R](s: Catenable[Segment[O,R]]) extends Segment[O,R] {
    def stage0(depth: Depth, defer: Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: R => Unit) = Eval.always {
      var res: Option[R] = None
      var ind = 0
      val staged = s.map(_.stage(depth.increment, defer, emit, emits, r => { res = Some(r); ind += 1 }).value)
      var i = staged
      def rem = catenated(i.map(_.remainder), res.get)
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

  private def stepAll(s: Step[Any,Any], trampoline: Trampoline): Unit = {
    s.step()
    val deferred = trampoline.deferred
    while (!deferred.isEmpty()) {
      val tc = deferred.remove()
      tc()
    }
  }

  final case class Depth(value: Int) extends AnyVal {
    def increment: Depth = Depth(value + 1)
    def <(that: Depth): Boolean = value < that.value
  }
  private val MaxFusionDepth: Depth = Depth(50)

  final type Defer = (=> Unit) => Unit

  private final case object Done extends RuntimeException { override def fillInStackTrace = this }

  private final class Trampoline {
    val deferred: JLinkedList[() => Unit] = new JLinkedList[() => Unit]
    def defer(t: => Unit): Unit = deferred.addLast(() => t)
  }
}
