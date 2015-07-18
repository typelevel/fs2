package scalaz.stream

import scala.annotation.tailrec
import scalaz.\/._
import scalaz._
import scalaz.syntax.equal._

import Cause._
import Process._
import Util._

object process1 {

  // nb: methods are in alphabetical order, there are going to be so many that
  // any other order will just going get confusing

  /** Await a single value, returning `None` if the input has been exhausted. */
  def awaitOption[I]: Process1[I, Option[I]] =
    receive1Or[I, Option[I]](emit(None))(i => emit(Some(i)))

  /** Behaves like the identity process, but requests `n` elements at a time from its input. */
  def buffer[I](n: Int): Process1[I, I] =
    chunk[I](n).flatMap(emitAll)

  /**
   * Behaves like the identity process, but requests elements from its
   * input in blocks that end whenever the predicate switches from true to false.
   */
  def bufferBy[I](f: I => Boolean): Process1[I, I] =
    chunkBy(f).flatMap(emitAll)

  /** Behaves like the identity process, but batches all output into a single `Emit`. */
  def bufferAll[I]: Process1[I, I] =
    chunkAll[I].flatMap(emitAll)

  /**
   * Groups inputs into chunks of size `n`. The last chunk may have size
   * less than `n`, depending on the number of elements in the input.
   *
   * @example {{{
   * scala> Process(1, 2, 3, 4, 5).chunk(2).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(3, 4), Vector(5))
   * }}}
   * @throws IllegalArgumentException if `n` <= 0
   */
  def chunk[I](n: Int): Process1[I, Vector[I]] = {
    require(n > 0, "chunk size must be > 0, was: " + n)
    def go(m: Int, acc: Vector[I]): Process1[I, Vector[I]] =
      if (m <= 0) emit(acc) ++ go(n, Vector())
      else receive1Or[I, Vector[I]](if (acc.nonEmpty) emit(acc) else halt) { i =>
        go(m - 1, acc :+ i)
      }
    go(n, Vector())
  }

  /** Collects up all output of this `Process1` into a single `Emit`. */
  def chunkAll[I]: Process1[I, Vector[I]] =
    chunkBy[I](_ => false)

  /**
   * Like `chunk`, but emits a chunk whenever the predicate switches from
   * true to false.
   * {{{
   * scala> Process(1, 2, -1, 3, 4).chunkBy(_ > 0).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2, -1), Vector(3, 4))
   * }}}
   */
  def chunkBy[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I], last: Boolean): Process1[I, Vector[I]] =
      receive1Or[I,Vector[I]](emit(acc)) { i =>
        val chunk = acc :+ i
        val cur = f(i)
        if (!cur && last) emit(chunk) ++ go(Vector(), false)
        else go(chunk, cur)
      }
    go(Vector(), false)
  }

  /**
   * Like `chunkBy`, but the predicate depends on the current and previous elements.
   */
  def chunkBy2[I](f: (I, I) => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I], last: I): Process1[I, Vector[I]] =
      receive1Or[I,Vector[I]](emit(acc)) { i =>
        if (f(last, i)) go(acc :+ i, i)
        else emit(acc) ++ go(Vector(i), i)
      }
    receive1(i => go(Vector(i), i))
  }

  /**
   * Like `collect` on scala collection.
   * Builds a new process by applying a partial function
   * to all elements of this process on which the function is defined.
   *
   * Elements, for which the partial function is not defined are
   * filtered out from new process
   */
  def collect[I, I2](pf: PartialFunction[I, I2]): Process1[I, I2] =
    id[I].flatMap(pf andThen (emit) orElse { case _ => halt })

  /**
   * Like `collect`, but emits only the first element of this process on which
   * the partial function is defined.
   */
  def collectFirst[I, I2](pf: PartialFunction[I, I2]): Process1[I, I2] =
    collect(pf).once

  /**
   * Skips the first element that matches the predicate.
   *
   * @example {{{
   * scala> Process(3, 4, 5, 6).delete(_ % 2 == 0).toList
   * res0: List[Int] = List(3, 5, 6)
   * }}}
   */
  def delete[I](f: I => Boolean): Process1[I, I] =
    receive1(i => if (f(i)) id else emit(i) ++ delete(f))

  /**
   * Remove any leading emitted values that occur before the first successful
   * `Await`. That means that the returned `Process1` will produce output only
   * if it has consumed at least one input element.
   */
  def drainLeading[A, B](p: Process1[A, B]): Process1[A, B] =
    receive1(a => feed1(a)(p))

  /**
   * Emits only elements that are distinct from their immediate predecessors.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> Process(1, 2, 2, 1, 1, 3).distinctConsecutive.toList
   * res0: List[Int] = List(1, 2, 1, 3)
   * }}}
   */
  def distinctConsecutive[A: Equal]: Process1[A, A] =
    distinctConsecutiveBy(identity)

  /**
   * Emits only elements that are distinct from their immediate predecessors
   * according to `f`.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> Process("a", "ab", "bc", "c", "d").distinctConsecutiveBy(_.length).toList
   * res0: List[String] = List(a, ab, c)
   * }}}
   */
  def distinctConsecutiveBy[A, B: Equal](f: A => B): Process1[A, A] =
    filterBy2((a1, a2) => f(a1) =/= f(a2))

  /** Skips the first `n` elements of the input, then passes through the rest. */
  def drop[I](n: Int): Process1[I, I] =
    if (n <= 0) id
    else skip ++ drop(n - 1)

  /** Emits all but the last element of the input. */
  def dropLast[I]: Process1[I, I] =
    dropLastIf(_ => true)

  /** Emits all elements of the input but skips the last if the predicate is true. */
  def dropLastIf[I](p: I => Boolean): Process1[I, I] = {
    def go(prev: I): Process1[I, I] =
      receive1Or[I,I](if (p(prev)) halt else emit(prev)) { i =>
        emit(prev) ++ go(i)
      }
    receive1(go)
  }

  /** Emits all but the last `n` elements of the input. */
  def dropRight[I](n: Int): Process1[I, I] = {
    def go(acc: Vector[I]): Process1[I, I] =
      receive1(i => emit(acc.head) ++ go(acc.tail :+ i))
    if (n <= 0) id
    else chunk(n).once.flatMap(go)
  }

  /**
   * Skips elements of the input while the predicate is true,
   * then passes through the remaining inputs.
   */
  def dropWhile[I](f: I => Boolean): Process1[I, I] =
    receive1(i => if (f(i)) dropWhile(f) else emit(i) ++ id)

  /** Feed a single input to a `Process1`. */
  def feed1[I, O](i: I)(p: Process1[I, O]): Process1[I, O] =
    feed(Seq(i))(p)

  /** Feed a sequence of inputs to a `Process1`. */
  def feed[I, O](i: Seq[I])(p: Process1[I, O]): Process1[I, O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[O] , cur: Process1[I, O]  ): Process1[I, O] = {
      if (in.nonEmpty) {
        cur.step match {
          case Step(Emit(os),cont) => go(in, out fast_++ os, cont.continue)
          case Step(Await1(rcv), cont) => go(in.tail,out,rcv(right(in.head)) +: cont)
          case Halt(rsn) =>  emitAll(out).causedBy(rsn)
        }
      } else cur.prepend(out)
    }
    go(i, Vector(), p)
  }

  /** Skips any elements of the input not matching the predicate. */
  def filter[I](f: I => Boolean): Process1[I, I] =
    id[I].flatMap(i => if (f(i)) emit(i) else halt)

  /**
   * Like `filter`, but the predicate `f` depends on the previously emitted and
   * current elements.
   *
   * @example {{{
   * scala> Process(2, 4, 1, 5, 3).filterBy2(_ < _).toList
   * res0: List[Int] = List(2, 4, 5)
   * }}}
   */
  def filterBy2[I](f: (I, I) => Boolean): Process1[I, I] = {
    def pass(i: I): Process1[I, I] =
      emit(i) ++ go(f(i, _))
    def go(g: I => Boolean): Process1[I, I] =
      receive1(i => if (g(i)) pass(i) else go(g))
    receive1(pass)
  }

  /**
   * Skips any elements not satisfying predicate and when found, will emit that
   * element and terminate
   */
  def find[I](f: I => Boolean): Process1[I, I] =
    filter(f).once

  /**
   * Halts with `true` as soon as a matching element is received.
   * Emits a single `false` if no input matches the predicate.
   */
  def exists[I](f: I => Boolean): Process1[I, Boolean] =
    forall[I](!f(_)).map(!_)

  /**
   * Emits a single `true` value if all input matches the predicate.
   * Halts with `false` as soon as a non-matching element is received.
   */
  def forall[I](f: I => Boolean): Process1[I, Boolean] =
    receive1Or[I,Boolean](emit(true)) { i =>
      if (f(i)) forall(f) else emit(false)
    }

  /**
   * `Process1` form of `List.fold`.
   * Folds the elements of this Process using the specified associative binary operator.
   *
   * Unlike List.fold the order is always from the `left` side, i.e. it will always
   * honor order of `A`.
   *
   * If Process of `A` is empty, it will just emit `z` and terminate
   * {{{
   * scala> Process(1, 2, 3, 4).fold(0)(_ + _).toList
   * res0: List[Int] = List(10)
   * }}}
   */
  def fold[A, B](z: B)(f: (B, A) => B): Process1[A, B] =
    scan(z)(f).last

  /** Alias for `[[reduce]](f)`. */
  def fold1[A](f: (A, A) => A): Process1[A, A] =
    reduce(f)

  /** Alias for `[[reduceMap]](f)(M)`. */
  def fold1Map[A, B](f: A => B)(implicit M: Monoid[B]): Process1[A, B] =
    reduceMap(f)(M)

  /** Alias for `[[reduceSemigroup]](M)`. */
  def fold1Monoid[A](implicit M: Monoid[A]): Process1[A, A] =
    reduceSemigroup(M)

  /**
   * Like `fold` only uses `f` to map `A` to `B` and uses Monoid `M` for associative operation
   */
  def foldMap[A, B](f: A => B)(implicit M: Monoid[B]): Process1[A, B] =
   lift(f).foldMonoid(M)

  /**
   * Like `fold` but uses Monoid for folding operation
   */
  def foldMonoid[A](implicit M: Monoid[A]): Process1[A, A] =
    fold(M.zero)(M.append(_, _))

  /** Alias for `[[reduceSemigroup]](M)`. */
  def foldSemigroup[A](implicit M: Semigroup[A]): Process1[A, A] =
    reduceSemigroup(M)

  /** Repeatedly echo the input; satisfies `x |> id == x` and `id |> x == x`. */
  def id[I]: Process1[I, I] =
    await1[I].repeat

  /**
   * Adds `separator` between elements of the input. For example,
   * {{{
   * scala> Process(1, 2, 3).intersperse(0).toList
   * res0: List[Int] = List(1, 0, 2, 0, 3)
   * }}}
   */
  def intersperse[A](separator: A): Process1[A, A] =
    await1[A] ++ id[A].flatMap(a => Process(separator, a))

  /** Skips all but the last element of the input. */
  def last[I]: Process1[I, I] = {
    def go(prev: I): Process1[I, I] = receive1Or[I,I](emit(prev))(go)
    receive1(go)
  }

  /**
   * Skips all but the last element of the input.
   * This `Process` will always emit exactly one value;
   * If the input is empty, `li` is emitted.
   */
  def lastOr[I](li: => I): Process1[I, I] =
    receive1Or[I,I](emit(li))(i => lastOr(i))

  /** Transform the input using the given function, `f`. */
  def lift[I, O](f: I => O): Process1[I, O] =
    id map f

  /**
   * Transform `p` to operate on the first element of a pair, passing
   * through the right value with no modifications. Note that this halts
   * whenever `p` halts.
   *
   * @param f function used to convert `B`s generated during cleanup of `p` to pairs
   */
  def liftFirst[A, B, C](f: B => Option[C])(p: Process1[A, B]): Process1[(A, C), (B, C)] = {
    def go(curr: Process1[A, B]): Process1[(A, C), (B, C)] = {
      val cleanup: Process1[(A, C), (B, C)] = curr.disconnect(Kill).flatMap(b => f(b) match {
        case Some(c) => emit((b, c))
        case None => halt
      })
      receive1Or[(A, C), (B, C)](cleanup) { case (a, c) =>
        val (emitted, next) = curr.feed1(a).unemit
        val out = emitAll(emitted).map((_, c))
        next match {
          case h @ Halt(_) => out fby h
          case other => out fby go(other)
        }
      }
    }
    go(p)
  }

  /**
   * Transform `p` to operate on the second element of a pair, passing
   * through the left value with no modifications. Note that this halts
   * whenever `p` halts.
   *
   * @param f function used to convert `B`s generated during cleanup of `p` to pairs
   */
  def liftSecond[A, B, C](f: B => Option[C])(p: Process1[A, B]): Process1[(C, A), (C, B)] =
    lift[(C, A), (A, C)](_.swap) |> liftFirst(f)(p).map(_.swap)

  /**
   * Transform `p` to operate on the left hand side of an `\/`, passing
   * through any values it receives on the right. Note that this halts
   * whenever `p` halts.
   */
  def liftL[A, B, C](p: Process1[A, B]): Process1[A \/ C, B \/ C] = {
    def go(curr: Process1[A,B]): Process1[A \/ C, B \/ C] = {
      receive1Or[A \/ C, B \/ C](curr.disconnect(Kill).map(-\/(_))) {
        case -\/(a) =>
          val (bs, next) = curr.feed1(a).unemit
          val out =  emitAll(bs).map(-\/(_))
          next match {
            case Halt(rsn) => out ++ Halt(rsn)
            case other => out ++ go(other)
          }
        case \/-(c) => emitO(c) ++ go(curr)
      }
    }
    go(p)
  }

  /**
   * Transform `p` to operate on the right hand side of an `\/`, passing
   * through any values it receives on the left. Note that this halts
   * whenever `p` halts.
   */
  def liftR[A, B, C](p: Process1[B, C]): Process1[A \/ B, A \/ C] =
    lift((e: A \/ B) => e.swap) |> liftL(p).map(_.swap)

  /**
   * Lifts Process1 to operate on Left side of `wye`, ignoring any right input.
   * Use `wye.flip` to convert it to right side
   */
  def liftY[I,O](p: Process1[I,O]) : Wye[I,Any,O] = {
    p.step match {
      case Step(Await(_,rcv, cln), cont) =>
        Await(L[I]: Env[I,Any]#Y[I],rcv, cln) onHalt(rsn=> liftY(Halt(rsn) +: cont))

      case Step(emt@Emit(os), cont) =>
        emt onHalt(rsn=> liftY(Halt(rsn) +: cont))

      case hlt@Halt(rsn) => hlt
    }
  }

  /**
   * Maps a running total according to `S` and the input with the function `f`.
   *
   * @example {{{
   * scala> Process("Hello", "World")
   *      |   .mapAccumulate(0)((l, s) => (l + s.length, s.head)).toList
   * res0: List[(Int, Char)] = List((5,H), (10,W))
   * }}}
   * @see [[zipWithScan1]]
   */
  def mapAccumulate[S, A, B](init: S)(f: (S, A) => (S, B)): Process1[A, (S, B)] =
    receive1 { a =>
      val sb = f(init, a)
      emit(sb) ++ mapAccumulate(sb._1)(f)
    }

  /** Emits the greatest element of the input. */
  def maximum[A](implicit A: Order[A]): Process1[A,A] =
    reduce((x, y) => if (A.greaterThan(x, y)) x else y)

  /** Emits the element `a` of the input which yields the greatest value of `f(a)`. */
  def maximumBy[A,B: Order](f: A => B): Process1[A,A] =
    reduce((x, y) => if (Order.orderBy(f).greaterThan(x, y)) x else y)

  /** Emits the greatest value of `f(a)` for each element `a` of the input. */
  def maximumOf[A,B: Order](f: A => B): Process1[A,B] =
    lift(f).maximum

  /** Emits the smallest element of the input. */
  def minimum[A](implicit A: Order[A]): Process1[A,A] =
    reduce((x, y) => if (A.lessThan(x, y)) x else y)

  /** Emits the element `a` of the input which yields the smallest value of `f(a)`. */
  def minimumBy[A,B: Order](f: A => B): Process1[A,A] =
    reduce((x, y) => if (Order.orderBy(f).lessThan(x, y)) x else y)

  /** Emits the smallest value of `f(a)` for each element `a` of the input. */
  def minimumOf[A,B: Order](f: A => B): Process1[A,B] =
    lift(f).minimum

  /**
   * Split the input and send to either `chanL` or `chanR`, halting when
   * either branch halts.
   *
   * @example {{{
   * scala> import scalaz.\/._
   * scala> import process1._
   * scala> Process(left(1), right('a'), left(2), right('b'))
   *      |   .pipe(multiplex(lift(_ * -1), lift(_.toInt))).toList
   * res0: List[Int] = List(-1, 97, -2, 98)
   * }}}
   */
  def multiplex[I, I2, O](chanL: Process1[I, O], chanR: Process1[I2, O]): Process1[I \/ I2, O] =
    (liftL(chanL) pipe liftR(chanR)).map(_.fold(identity, identity))

  /**
   * Emits the sums of prefixes (running totals) of the input elements.
   * The first value emitted will always be zero.
   *
   * @example {{{
   * scala> Process(1, 2, 3).prefixSums.toList
   * res0: List[Int] = List(0, 1, 3, 6)
   *
   * scala> Process[Int]().prefixSums.toList
   * res1: List[Int] = List(0)
   * }}}
   */
  def prefixSums[N](implicit N: Numeric[N]): Process1[N,N] =
    scan(N.zero)(N.plus)

  /**
   * `Process1` form of `List.reduce`.
   *
   * Reduces the elements of this Process using the specified associative binary operator.
   * {{{
   * scala> Process(1, 2, 3, 4).reduce(_ + _).toList
   * res0: List[Int] = List(10)
   *
   * scala> Process(1).reduce(_ + _).toList
   * res1: List[Int] = List(1)
   *
   * scala> Process[Int]().reduce(_ + _).toList
   * res2: List[Int] = List()
   * }}}
   *
   * Unlike `List.reduce` will not fail when Process is empty.
   */
  def reduce[A](f: (A, A) => A): Process1[A, A] =
    scan1(f).last

  /**
   * Like `reduce` only uses `f` to map `A` to `B` and uses Semigroup `M` for
   * associative operation.
   */
  def reduceMap[A, B](f: A => B)(implicit M: Semigroup[B]): Process1[A, B] =
    lift(f).reduceSemigroup(M)

  /** Alias for `[[reduceSemigroup]](M)`. */
  def reduceMonoid[A](implicit M: Monoid[A]): Process1[A, A] =
    reduceSemigroup(M)

  /** Like `reduce` but uses Semigroup `M` for associative operation. */
  def reduceSemigroup[A](implicit M: Semigroup[A]): Process1[A, A] =
    reduce(M.append(_, _))

  /**
   * Repartitions the input with the function `p`. On each step `p` is applied
   * to the input and all elements but the last of the resulting sequence
   * are emitted. The last element is then prepended to the next input using the
   * Semigroup `I`. For example,
   * {{{
   * scala> import scalaz.std.string._
   * scala> Process("Hel", "l", "o Wor", "ld").repartition(_.split(" ")).toList
   * res0: List[String] = List(Hello, World)
   * }}}
   */
  def repartition[I](p: I => IndexedSeq[I])(implicit I: Semigroup[I]): Process1[I, I] = {
    def go(carry: Option[I]): Process1[I, I] =
      receive1Or[I,I](emitAll(carry.toList)) { i =>
        val next = carry.fold(i)(c => I.append(c, i))
        val parts = p(next)
        parts.size match {
          case 0 => go(None)
          case 1 => go(Some(parts.head))
          case _ => emitAll(parts.init) ++ go(Some(parts.last))
        }
      }
    go(None)
  }

  /**
   * Repartitions the input with the function `p`. On each step `p` is applied
   * to the input and the first element of the resulting tuple is emitted if it
   * is `Some(x)`. The second element is then prepended to the next input using
   * the Semigroup `I`. In comparison to `repartition` this allows to emit
   * single inputs without prepending them to the next input.
   */
  def repartition2[I](p: I => (Option[I], Option[I]))(implicit I: Semigroup[I]): Process1[I,I] = {
    def go(carry: Option[I]): Process1[I,I] =
      receive1Or[I,I](emitAll(carry.toList)) { i =>
        val next = carry.fold(i)(c => I.append(c, i))
        val (fst, snd) = p(next)
        fst.fold(go(snd))(head => emit(head) ++ go(snd))
      }
    go(None)
  }

  /** Throws any input exceptions and passes along successful results. */
  def rethrow[A]: Process1[Throwable \/ A, A] =
    id[Throwable \/ A].flatMap {
      case -\/(err) => throw err
      case \/-(a)   => emit(a)
    }

  def stateScan[S, A, B](init: S)(f: A => State[S, B]): Process1[A, B] = {
    await1[A] flatMap { a =>
      val (s, b) = f(a) run init
      emit(b) ++ stateScan(s)(f)
    }
  }

  /**
   * Similar to List.scan.
   * Produces a process of `B` containing cumulative results of applying the operator to Process of `A`.
   * It will always emit `z`, even when the Process of `A` is empty
   */
  def scan[A, B](z: B)(f: (B, A) => B): Process1[A, B] =
    emit(z) ++ receive1(a => scan(f(z, a))(f))

  /**
   * Similar to `scan`, but unlike it it won't emit the `z` even when there is no input of `A`.
   * {{{
   * scala> Process(1, 2, 3, 4).scan1(_ + _).toList
   * res0: List[Int] = List(1, 3, 6, 10)
   *
   * scala> Process(1).scan1(_ + _).toList
   * res1: List[Int] = List(1)
   *
   * scala> Process[Int]().scan1(_ + _).toList
   * res2: List[Int] = List()
   * }}}
   */
  def scan1[A](f: (A, A) => A): Process1[A, A] =
    receive1(a => scan(a)(f))

  /**
   * Like `scan1` only uses `f` to map `A` to `B` and uses Semigroup `M` for
   * associative operation.
   */
  def scan1Map[A, B](f: A => B)(implicit M: Semigroup[B]): Process1[A, B] =
    lift(f).scanSemigroup(M)

  /** Alias for `[[scanSemigroup]](M)`. */
  def scan1Monoid[A](implicit M: Monoid[A]): Process1[A, A] =
    scanSemigroup(M)

  /**
   * Like `scan` only uses `f` to map `A` to `B` and uses Monoid `M` for associative operation
   */
  def scanMap[A, B](f: A => B)(implicit M: Monoid[B]): Process1[A, B] =
    lift(f).scanMonoid(M)

  /**
   * Like `scan` but uses Monoid for associative operation
   */
  def scanMonoid[A](implicit M: Monoid[A]): Process1[A, A] =
    scan(M.zero)(M.append(_, _))

  /** Like `scan1` but uses Semigroup `M` for associative operation. */
  def scanSemigroup[A](implicit M: Semigroup[A]): Process1[A, A] =
    scan1(M.append(_, _))

  /**
   * Emit the given values, then echo the rest of the input.
   *
   * @example {{{
   * scala> Process(3, 4).shiftRight(1, 2).toList
   * res0: List[Int] = List(1, 2, 3, 4)
   * }}}
   */
  def shiftRight[I](head: I*): Process1[I, I] =
    emitAll(head) ++ id

  /**
   * Reads a single element of the input, emits nothing, then halts.
   *
   * @example {{{
   * scala> import process1._
   * scala> Process(1, 2, 3).pipe(skip ++ id).toList
   * res0: List[Int] = List(2, 3)
   * }}}
   */
  def skip: Process1[Any, Nothing] =
    receive1(_ => halt)

  /**
   * Groups inputs in fixed size chunks by passing a "sliding window"
   * of size `n` over them. If the input contains less than or equal to
   * `n` elements, only one chunk of this size will be emitted.
   *
   * @example {{{
   * scala> Process(1, 2, 3, 4).sliding(2).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(2, 3), Vector(3, 4))
   * }}}
   * @throws IllegalArgumentException if `n` <= 0
   */
  def sliding[I](n: Int): Process1[I, Vector[I]] = {
    require(n > 0, "window size must be > 0, was: " + n)
    def go(window: Vector[I]): Process1[I, Vector[I]] =
      emit(window) ++ receive1(i => go(window.tail :+ i))
    chunk(n).once.flatMap(go)
  }

  /**
   * Break the input into chunks where the delimiter matches the predicate.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def split[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I]): Process1[I, Vector[I]] =
      receive1Or[I, Vector[I]](emit(acc)) { i =>
        if (f(i)) emit(acc) ++ go(Vector())
        else go(acc :+ i)
      }
    go(Vector())
  }

  /**
   * Break the input into chunks where the input is equal to the given delimiter.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def splitOn[I: Equal](i: I): Process1[I, Vector[I]] =
    split(_ === i)

  /**
   * Breaks the input into chunks that alternatively satisfy and don't satisfy
   * the predicate `f`.
   *
   * @example {{{
   * scala> Process(1, 2, -3, -4, 5, 6).splitWith(_ < 0).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(-3, -4), Vector(5, 6))
   * }}}
   */
  def splitWith[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I], last: Boolean): Process1[I, Vector[I]] =
      receive1Or[I, Vector[I]](emit(acc)) { i =>
         val cur = f(i)
         if (cur == last) go(acc :+ i, cur)
         else emit(acc) ++ go(Vector(i), cur)
      }
    receive1(i => go(Vector(i), f(i)))
  }

  /** Remove any `None` inputs. */
  def stripNone[A]: Process1[Option[A], A] =
    collect { case Some(a) => a }

  /**
   * Emits the sum of all input elements or zero if the input is empty.
   *
   * @example {{{
   * scala> Process(1, 2, 3).sum.toList
   * res0: List[Int] = List(6)
   *
   * scala> Process[Int]().sum.toList
   * res1: List[Int] = List(0)
   * }}}
   */
  def sum[N](implicit N: Numeric[N]): Process1[N,N] =
    fold(N.zero)(N.plus)

  /**
   * Emits all elements of the input except the first one.
   *
   * @example {{{
   * scala> Process(1, 2, 3).tail.toList
   * res0: List[Int] = List(2, 3)
   *
   * scala> Process[Int]().tail.toList
   * res1: List[Int] = List()
   * }}}
   */
  def tail[I]: Process1[I, I] =
    receive1(_ => id)

  /** Passes through `n` elements of the input, then halts. */
  def take[I](n: Int): Process1[I, I] =
    if (n <= 0) halt
    else await1[I] ++ take(n - 1)

  /** Emits the last `n` elements of the input. */
  def takeRight[I](n: Int): Process1[I, I] = {
    def go(acc: Vector[I]): Process1[I, I] =
      receive1Or[I, I](emitAll(acc))(i => go(acc.tail :+ i))
    if (n <= 0) halt
    else chunk(n).once.flatMap(go)
  }

  /** Passes through elements of the input as long as the predicate is true, then halts. */
  def takeWhile[I](f: I => Boolean): Process1[I, I] =
    receive1 (i => if (f(i)) emit(i) ++ takeWhile(f) else halt)

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[I](f: I => Boolean): Process1[I, I] =
    receive1 (i => if (f(i)) emit(i) ++ takeThrough(f) else emit(i))

  /** Wraps all inputs in `Some`, then outputs a single `None` before halting. */
  def terminated[A]: Process1[A, Option[A]] =
     lift[A, Option[A]](Some(_)) onComplete emit(None)

  /**
   * Ungroups chunked input.
   *
   * @example {{{
   * scala> Process(Seq(1, 2), Seq(3)).pipe(process1.unchunk).toList
   * res0: List[Int] = List(1, 2, 3)
   * }}}
   */
  def unchunk[I]: Process1[Seq[I], I] =
    id[Seq[I]].flatMap(emitAll)

  /** Zips the input with an index of type `Int`. */
  def zipWithIndex[A]: Process1[A,(A,Int)] =
    zipWithIndex[A,Int]

  /** Zips the input with an index of type `N`. */
  def zipWithIndex[A,N](implicit N: Numeric[N]): Process1[A,(A,N)] =
    zipWithState(N.zero)((_, n) => N.plus(n, N.one))

  /**
   * Zips every element with its previous element wrapped into `Some`.
   * The first element is zipped with `None`.
   */
  def zipWithPrevious[I]: Process1[I,(Option[I],I)] =
    zipWithState[I,Option[I]](None)((cur, _) => Some(cur)).map(_.swap)

  /**
   * Zips every element with its next element wrapped into `Some`.
   * The last element is zipped with `None`.
   */
  def zipWithNext[I]: Process1[I,(I,Option[I])] = {
    def go(prev: I): Process1[I,(I,Option[I])] =
      receive1Or[I,(I,Option[I])](emit((prev, None)))(i => emit((prev, Some(i))) ++ go(i))
    receive1(go)
  }

  /**
   * Zips every element with its previous and next elements wrapped into `Some`.
   * The first element is zipped with `None` as the previous element,
   * the last element is zipped with `None` as the next element.
   */
  def zipWithPreviousAndNext[I]: Process1[I,(Option[I],I,Option[I])] =
    zipWithPrevious.pipe(zipWithNext[(Option[I],I)]).map {
      case ((previous, current), None)            =>  (previous, current, None)
      case ((previous, current), Some((_, next))) =>  (previous, current, Some(next))
    }

  /**
   * Zips the input with a running total according to `B`, up to but not including the
   * current element. Thus the initial `z` value is the first emitted to the output:
   *
   * {{{
   * scala> Process("uno", "dos", "tres", "cuatro").zipWithScan(0)(_.length + _).toList
   * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
   * }}}
   *
   * @see [[zipWithScan1]]
   */
  def zipWithScan[A,B](z: B)(f: (A,B) => B): Process1[A,(A,B)] =
   zipWithState(z)(f)

  /**
   * Zips the input with a running total according to `B`, up to and including the
   * current element. Thus the initial `z` value is not emitted to the output:
   *
   * {{{
   * scala> Process("uno", "dos", "tres", "cuatro").zipWithScan1(0)(_.length + _).toList
   * res0: List[(String,Int)] = List((uno,3), (dos,6), (tres,10), (cuatro,16))
   * }}}
   *
   * @see [[zipWithScan]]
   */
  def zipWithScan1[A,B](z: B)(f: (A,B) => B): Process1[A,(A,B)] =
    receive1 { a =>
      val z2 = f(a,z)
      emit((a,z2)) ++ zipWithScan1(z2)(f)
    }

  /** Zips the input with state that begins with `z` and is updated by `next`. */
  def zipWithState[A,B](z: B)(next: (A, B) => B): Process1[A,(A,B)] =
    receive1(a => emit((a, z)) ++ zipWithState(next(a, z))(next))

  object Await1 {
    /** deconstruct for `Await` directive of `Process1` */
    def unapply[I, O](self: Process1[I, O]): Option[EarlyCause \/ I => Process1[I, O]] = self match {
      case Await(_, rcv,_) => Some((r:EarlyCause\/ I) => Try(rcv(r).run))
      case _             => None
    }

  }



}

final class Process1Syntax[I, O](val self: Process1[I, O]) extends AnyVal {

  /** Apply this `Process` to an `Iterable`. */
  def apply(input: Iterable[I]): IndexedSeq[O] =
    Process(input.toSeq: _*).pipe(self).toIndexedSeq

  /**
   * Transform `self` to operate on the left hand side of an `\/`, passing
   * through any values it receives on the right. Note that this halts
   * whenever `self` halts.
   */
  def liftL[I2]: Process1[I \/ I2, O \/ I2] =
    process1.liftL(self)

  /**
   * Transform `self` to operate on the right hand side of an `\/`, passing
   * through any values it receives on the left. Note that this halts
   * whenever `self` halts.
   */
  def liftR[I0]: Process1[I0 \/ I, I0 \/ O] =
    process1.liftR(self)

  /**
   * Feed a single input to this `Process1`.
   */
  def feed1(i: I): Process1[I,O] =
    process1.feed1(i)(self)

  /** Transform the input of this `Process1`. */
  def contramap[I0](f: I0 => I): Process1[I0, O] =
    process1.lift(f).pipe(self)

}

private[stream] trait Process1Ops[+F[_],+O] {
  self: Process[F,O] =>

  /** Alias for `this |> [[process1.awaitOption]]`. */
  def awaitOption: Process[F,Option[O]] =
    this |> process1.awaitOption

  /** Alias for `this |> [[process1.buffer]](n)`. */
  def buffer(n: Int): Process[F,O] =
    this |> process1.buffer(n)

  /** Alias for `this |> [[process1.bufferAll]]`. */
  def bufferAll: Process[F,O] =
    this |> process1.bufferAll

  /** Alias for `this |> [[process1.bufferBy]](f)`. */
  def bufferBy(f: O => Boolean): Process[F,O] =
    this |> process1.bufferBy(f)

  /** Alias for `this |> [[process1.chunk]](n)`. */
  def chunk(n: Int): Process[F,Vector[O]] =
    this |> process1.chunk(n)

  /** Alias for `this |> [[process1.chunkAll]]`. */
  def chunkAll: Process[F,Vector[O]] =
    this |> process1.chunkAll

  /** Alias for `this |> [[process1.chunkBy]](f)`. */
  def chunkBy(f: O => Boolean): Process[F,Vector[O]] =
    this |> process1.chunkBy(f)

  /** Alias for `this |> [[process1.chunkBy2]](f)`. */
  def chunkBy2(f: (O, O) => Boolean): Process[F,Vector[O]] =
    this |> process1.chunkBy2(f)

  /** Alias for `this |> [[process1.collect]](pf)`. */
  def collect[O2](pf: PartialFunction[O,O2]): Process[F,O2] =
    this |> process1.collect(pf)

  /** Alias for `this |> [[process1.collectFirst]](pf)`. */
  def collectFirst[O2](pf: PartialFunction[O,O2]): Process[F,O2] =
    this |> process1.collectFirst(pf)

  /** Alias for `this |> [[process1.delete]](f)`. */
  def delete(f: O => Boolean): Process[F,O] =
    this |> process1.delete(f)

  /** Alias for `this |> [[process1.distinctConsecutive]]`. */
  def distinctConsecutive[O2 >: O](implicit O2: Equal[O2]): Process[F,O2] =
    this |> process1.distinctConsecutive(O2)

  /** Alias for `this |> [[process1.distinctConsecutiveBy]](f)`. */
  def distinctConsecutiveBy[B: Equal](f: O => B): Process[F,O] =
    this |> process1.distinctConsecutiveBy(f)

  /** Alias for `this |> [[process1.drop]](n)`. */
  def drop(n: Int): Process[F,O] =
    this |> process1.drop(n)

  /** Alias for `this |> [[process1.dropLast]]`. */
  def dropLast: Process[F,O] =
    this |> process1.dropLast

  /** Alias for `this |> [[process1.dropLastIf]](p)`. */
  def dropLastIf(p: O => Boolean): Process[F,O] =
    this |> process1.dropLastIf(p)

  /** Alias for `this |> [[process1.dropRight]](n)`. */
  def dropRight(n: Int): Process[F,O] =
    this |> process1.dropRight(n)

  /** Alias for `this |> [[process1.dropWhile]](f)`. */
  def dropWhile(f: O => Boolean): Process[F,O] =
    this |> process1.dropWhile(f)

  /** Alias for `this |> [[process1.exists]](f)` */
  def exists(f: O => Boolean): Process[F,Boolean] =
    this |> process1.exists(f)

  /** Alias for `this |> [[process1.filter]](f)`. */
  def filter(f: O => Boolean): Process[F,O] =
    this |> process1.filter(f)

  /** Alias for `this |> [[process1.filterBy2]](f)`. */
  def filterBy2(f: (O, O) => Boolean): Process[F,O] =
    this |> process1.filterBy2(f)

  /** Alias for `this |> [[process1.find]](f)` */
  def find(f: O => Boolean): Process[F,O] =
    this |> process1.find(f)

  /** Alias for `this |> [[process1.forall]](f)` */
  def forall(f: O => Boolean): Process[F,Boolean] =
    this |> process1.forall(f)

  /** Alias for `this |> [[process1.fold]](b)(f)`. */
  def fold[B](b: B)(f: (B, O) => B): Process[F, B] =
    this |> process1.fold(b)(f)

  /** Alias for `this |> [[process1.foldMap]](f)(M)`. */
  def foldMap[M](f: O => M)(implicit M: Monoid[M]): Process[F,M] =
    this |> process1.foldMap(f)(M)

  /** Alias for `this |> [[process1.foldMonoid]](M)` */
  def foldMonoid[O2 >: O](implicit M: Monoid[O2]): Process[F,O2] =
    this |> process1.foldMonoid(M)

  /** Alias for `this |> [[process1.foldSemigroup]](M)`. */
  def foldSemigroup[O2 >: O](implicit M: Semigroup[O2]): Process[F,O2] =
    this |> process1.foldSemigroup(M)

  /** Alias for `this |> [[process1.fold1]](f)`. */
  def fold1[O2 >: O](f: (O2,O2) => O2): Process[F,O2] =
    this |> process1.fold1(f)

  /** Alias for `this |> [[process1.fold1Map]](f)(M)`. */
  def fold1Map[M](f: O => M)(implicit M: Monoid[M]): Process[F,M] =
    this |> process1.fold1Map(f)(M)

  /** Alias for `this |> [[process1.fold1Monoid]](M)` */
  def fold1Monoid[O2 >: O](implicit M: Monoid[O2]): Process[F,O2] =
    this |> process1.fold1Monoid(M)

  /** Alias for `this |> [[process1.intersperse]](sep)`. */
  def intersperse[O2>:O](sep: O2): Process[F,O2] =
    this |> process1.intersperse(sep)

  /** Alias for `this |> [[process1.last]]`. */
  def last: Process[F,O] =
    this |> process1.last

  /** Alias for `this |> [[process1.last]]`. */
  def lastOr[O2 >: O](o: => O2): Process[F,O2] =
    this |> process1.lastOr(o)

  /** Alias for `this |> [[process1.mapAccumulate]](s)(f)`. */
  def mapAccumulate[S, B](s: S)(f: (S, O) => (S, B)): Process[F, (S, B)] =
    this |> process1.mapAccumulate(s)(f)

  /** Alias for `this |> [[process1.maximum]]`. */
  def maximum[O2 >: O](implicit O2: Order[O2]): Process[F,O2] =
    this |> process1.maximum(O2)

  /** Alias for `this |> [[process1.maximumBy]](f)`. */
  def maximumBy[B: Order](f: O => B): Process[F,O] =
    this |> process1.maximumBy(f)

  /** Alias for `this |> [[process1.maximumOf]](f)`. */
  def maximumOf[B: Order](f: O => B): Process[F,B] =
    this |> process1.maximumOf(f)

  /** Alias for `this |> [[process1.minimum]]`. */
  def minimum[O2 >: O](implicit O2: Order[O2]): Process[F,O2] =
    this |> process1.minimum(O2)

  /** Alias for `this |> [[process1.minimumBy]](f)`. */
  def minimumBy[B: Order](f: O => B): Process[F,O] =
    this |> process1.minimumBy(f)

  /** Alias for `this |> [[process1.minimumOf]](f)`. */
  def minimumOf[B: Order](f: O => B): Process[F,B] =
    this |> process1.minimumOf(f)

  /** Alias for `this |> [[Process.await1]]`. */
  def once: Process[F,O] =
    this |> Process.await1

  /** Alias for `this |> [[process1.prefixSums]]` */
  def prefixSums[O2 >: O](implicit N: Numeric[O2]): Process[F,O2] =
    this |> process1.prefixSums(N)

  /** Alias for `this |> [[process1.reduce]](f)`. */
  def reduce[O2 >: O](f: (O2,O2) => O2): Process[F,O2] =
    this |> process1.reduce(f)

  /** Alias for `this |> [[process1.reduceMap]](f)(M)`. */
  def reduceMap[M](f: O => M)(implicit M: Semigroup[M]): Process[F,M] =
    this |> process1.reduceMap(f)(M)

  /** Alias for `this |> [[process1.reduceMonoid]](M)`. */
  def reduceMonoid[O2 >: O](implicit M: Monoid[O2]): Process[F,O2] =
    this |> process1.reduceMonoid(M)

  /** Alias for `this |> [[process1.reduceSemigroup]](M)`. */
  def reduceSemigroup[O2 >: O](implicit M: Semigroup[O2]): Process[F,O2] =
    this |> process1.reduceSemigroup(M)

  /** Alias for `this |> [[process1.repartition]](p)(S)` */
  def repartition[O2 >: O](p: O2 => IndexedSeq[O2])(implicit S: Semigroup[O2]): Process[F,O2] =
    this |> process1.repartition(p)(S)

  /** Alias for `this |> [[process1.repartition2]](p)(S)` */
  def repartition2[O2 >: O](p: O2 => (Option[O2], Option[O2]))(implicit S: Semigroup[O2]): Process[F,O2] =
    this |> process1.repartition2(p)(S)

  /** Alias for `this |> [[process1.scan]](b)(f)`. */
  def scan[B](b: B)(f: (B,O) => B): Process[F,B] =
    this |> process1.scan(b)(f)

  /** Alias for `this |> [[process1.stateScan]](init)(f)`. */
  def stateScan[S, B](init: S)(f: O => State[S, B]): Process[F, B] =
    this |> process1.stateScan(init)(f)

  /** Alias for `this |> [[process1.scanMap]](f)(M)`. */
  def scanMap[M](f: O => M)(implicit M: Monoid[M]): Process[F,M] =
    this |> process1.scanMap(f)(M)

  /** Alias for `this |> [[process1.scanMonoid]](M)`. */
  def scanMonoid[O2 >: O](implicit M: Monoid[O2]): Process[F,O2] =
    this |> process1.scanMonoid(M)

  /** Alias for `this |> [[process1.scanSemigroup]](M)`. */
  def scanSemigroup[O2 >: O](implicit M: Semigroup[O2]): Process[F,O2] =
    this |> process1.scanSemigroup(M)

  /** Alias for `this |> [[process1.scan1]](f)`. */
  def scan1[O2 >: O](f: (O2,O2) => O2): Process[F,O2] =
    this |> process1.scan1(f)

  /** Alias for `this |> [[process1.scan1Map]](f)(M)`. */
  def scan1Map[M](f: O => M)(implicit M: Semigroup[M]): Process[F,M] =
    this |> process1.scan1Map(f)(M)

  /** Alias for `this |> [[process1.scan1Monoid]](M)`. */
  def scan1Monoid[O2 >: O](implicit M: Monoid[O2]): Process[F,O2] =
    this |> process1.scan1Monoid(M)

  /** Alias for `this |> [[process1.shiftRight]](head)` */
  def shiftRight[O2 >: O](head: O2*): Process[F,O2] =
    this |> process1.shiftRight(head: _*)

  /** Alias for `this |> [[process1.sliding]](n)`. */
  def sliding(n: Int): Process[F,Vector[O]] =
    this |> process1.sliding(n)

  /** Alias for `this |> [[process1.split]](f)` */
  def split(f: O => Boolean): Process[F,Vector[O]] =
    this |> process1.split(f)

  /** Alias for `this |> [[process1.splitOn]](p)` */
  def splitOn[P >: O](p: P)(implicit P: Equal[P]): Process[F,Vector[P]] =
    this |> process1.splitOn(p)

  /** Alias for `this |> [[process1.splitWith]](f)` */
  def splitWith(f: O => Boolean): Process[F,Vector[O]] =
    this |> process1.splitWith(f)

  /** Alias for `this |> [[process1.sum]]` */
  def sum[O2 >: O](implicit N: Numeric[O2]): Process[F,O2] =
    this |> process1.sum(N)

  /** Alias for `this |> [[process1.tail]]`. */
  def tail: Process[F,O] =
    this |> process1.tail

  /** Alias for `this |> [[process1.take]](n)`. */
  def take(n: Int): Process[F,O] =
    this |> process1.take(n)

  /** Alias for `this |> [[process1.takeRight]](n)`. */
  def takeRight(n: Int): Process[F,O] =
    this |> process1.takeRight(n)

  /** Alias for `this |> [[process1.takeThrough]](f)`. */
  def takeThrough(f: O => Boolean): Process[F,O] =
    this |> process1.takeThrough(f)

  /** Alias for `this |> [[process1.takeWhile]](f)`. */
  def takeWhile(f: O => Boolean): Process[F,O] =
    this |> process1.takeWhile(f)

  /** Alias for `this |> [[process1.terminated]]`. */
  def terminated: Process[F,Option[O]] =
    this |> process1.terminated

  /** Alias for `this |> [[process1.zipWithIndex[A]*]]`. */
  def zipWithIndex: Process[F,(O,Int)] =
    this |> process1.zipWithIndex

  /** Alias for `this |> [[process1.zipWithIndex[A,N]*]]`. */
  def zipWithIndex[N: Numeric]: Process[F,(O,N)] =
    this |> process1.zipWithIndex[O,N]

  /** Alias for `this |> [[process1.zipWithPrevious]]`. */
  def zipWithPrevious: Process[F,(Option[O],O)] =
    this |> process1.zipWithPrevious

  /** Alias for `this |> [[process1.zipWithNext]]`. */
  def zipWithNext: Process[F,(O,Option[O])] =
    this |> process1.zipWithNext

  /** Alias for `this |> [[process1.zipWithPreviousAndNext]]`. */
  def zipWithPreviousAndNext: Process[F,(Option[O],O,Option[O])] =
    this |> process1.zipWithPreviousAndNext

  /** Alias for `this |> [[process1.zipWithScan]](z)(next)`. */
  def zipWithScan[B](z: B)(next: (O, B) => B): Process[F,(O,B)] =
    this |> process1.zipWithScan(z)(next)

  /** Alias for `this |> [[process1.zipWithScan]](z)(next)`. */
  def zipWithScan1[B](z: B)(next: (O, B) => B): Process[F,(O,B)] =
    this |> process1.zipWithScan1(z)(next)

  /** Alias for `this |> [[process1.zipWithState]](z)(next)`. */
  def zipWithState[B](z: B)(next: (O, B) => B): Process[F,(O,B)] =
    this |> process1.zipWithState(z)(next)
}
