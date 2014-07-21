package scalaz.stream

import java.nio.charset.Charset

import scala.annotation.tailrec
import scala.collection.immutable.Vector
import scalaz.\/._
import scalaz._
import scalaz.syntax.equal._


object process1 {

  import scalaz.stream.Process._
  import scalaz.stream.Util._

  // nb: methods are in alphabetical order, there are going to be so many that
  // any other order will just going get confusing

  /** Await a single value, returning `None` if the input has been exhausted. */
  def awaitOption[I]: Process1[I, Option[I]] =
    receive1Or[I, Option[I]](emit(None))(i=>emit(Some(i)))

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
   * less then `n`, depending on the number of elements in the input.
   *
   * @throws IllegalArgumentException if `n` <= 0
   */
  def chunk[I](n: Int): Process1[I, Vector[I]] = {
    require(n > 0, "chunk size must be > 0, was: " + n)
    def go(m: Int, acc: Vector[I]): Process1[I, Vector[I]] = {
      if (m <= 0) emit(acc)
      else receive1Or[I, Vector[I]](if (acc.nonEmpty) emit(acc) else halt) { i =>
        go(m - 1, acc :+ i)
      }
    }
    go(n,Vector()) fby chunk(n)
  }


  /** Collects up all output of this `Process1` into a single `Emit`. */
  def chunkAll[I]: Process1[I, Vector[I]] =
    chunkBy[I](_ => false)

  /**
   * Like `chunk`, but emits a chunk whenever the predicate switches from
   * true to false.
   * {{{
   * Process(1,2,-1,3,4).chunkBy(_ > 0).toList == List(Vector(1, 2, -1), Vector(3, 4))
   * }}}
   */
  def chunkBy[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I], last: Boolean): Process1[I, Vector[I]] =
      receive1Or[I,Vector[I]](emit(acc)) { i =>
        val chunk = acc :+ i
        val cur = f(i)
        if (!cur && last) emit(chunk) fby go(Vector(), false)
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
        else emit(acc) fby go(Vector(i), i)
      }
    await1[I].flatMap(i => go(Vector(i), i))
  }

  /**
   * Like `collect` on scala collection.
   * Builds a new process by applying a partial function
   * to all elements of this process on which the function is defined.
   *
   * Elements, for which the partial function is not defined are
   * filtered out from new process
   *
   */
  def collect[I, I2](pf: PartialFunction[I, I2]): Process1[I, I2] =
    id[I].flatMap(pf andThen (emit) orElse { case _ => halt })

  /**
   * Like `collect`, but emits only the first element of this process on which
   * the partial function is defined.
   */
  def collectFirst[I, I2](pf: PartialFunction[I, I2]): Process1[I, I2] =
    collect(pf).take(1)

  /**
   * Remove any leading emitted values that occur before the first successful
   * `Await`. That means that the returned `Process1` will produce output only
   * if it has consumed at least one input element.
   */
  def drainLeading[A, B](p: Process1[A, B]): Process1[A, B] =
    await1[A].flatMap(a => feed1(a)(p))

  /** Skips the first `n` elements of the input, then passes through the rest. */
  def drop[I](n: Int): Process1[I, I] =
    if (n <= 0) id[I]
    else skip fby drop(n - 1)

  /** Emits all but the last element of the input. */
  def dropLast[I]: Process1[I, I] =
    dropLastIf(_ => true)

  /** Emits all elements of the input but skips the last if the predicate is true. */
  def dropLastIf[I](p: I => Boolean): Process1[I, I] = {
    def go(prev: I): Process1[I, I] =
      receive1Or[I,I](if (p(prev)) halt else emit(prev))( i =>
        emit(prev) fby go(i)
      )
    await1[I].flatMap(go)
  }

  /**
   * Skips elements of the input while the predicate is true,
   * then passes through the remaining inputs.
   */
  def dropWhile[I](f: I => Boolean): Process1[I, I] =
    await1[I] flatMap (i => if (f(i)) dropWhile(f) else emit(i) fby id)

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
      } else cur.feed(out)

    }

    go(i, Vector(), p)
  }


  /** Skips any elements of the input not matching the predicate. */
  def filter[I](f: I => Boolean): Process1[I, I] =
    await1[I] flatMap (i => if (f(i)) emit(i) else halt) repeat

  /**
   * Skips any elements not satisfying predicate and when found, will emit that
   * element and terminate
   */
  def find[I](f: I => Boolean): Process1[I, I] =
    await1[I] flatMap (i => if (f(i)) emit(i) else find(f))

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
    receive1Or[I,Boolean](emit(true))( i =>
      if (f(i)) forall(f) else emit(false)
    )

  /**
   * `Process1` form of `List.fold`.
   * Folds the elements of this Process using the specified associative binary operator.
   *
   * Unlike List.fold the order is always from the `left` side, i.e. it will always
   * honor order of `A`.
   *
   * If Process of `A` is empty, it will just emit `z` and terminate
   * {{{
   * Process(1,2,3,4) |> fold(0)(_ + _) == Process(10)
   * }}}
   */
  def fold[A, B](z: B)(f: (B, A) => B): Process1[A, B] =
    scan(z)(f).last

  /**
   * `Process1` form of `List.reduce`.
   *
   * Reduces the elements of this Process using the specified associative binary operator.
   * {{{
   * Process(1,2,3,4) |> reduce(_ + _) == Process(10)
   * Process(1) |> reduce(_ + _) == Process(1)
   * Process() |> reduce(_ + _) == Process()
   * }}}
   */
  def fold1[A](f: (A, A) => A): Process1[A, A] =
    reduce(f)

  /**
   * Like `fold1` only uses `f` to map `A` to `B` and uses Monoid `M` for associative operation
   */
  def fold1Map[A, B](f: A => B)(implicit M: Monoid[B]): Process1[A, B] =
    reduceMap(f)(M)

  /** Like `fold1` but uses Monoid `M` for associative operation. */
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

  /** Alias for `reduceSemigroup`. */
  def foldSemigroup[A](implicit M: Semigroup[A]): Process1[A, A] =
    reduceSemigroup(M)

  /** Repeatedly echo the input; satisfies `x |> id == x` and `id |> x == x`. */
  def id[I]: Process1[I, I] =
    await1[I].repeat

  /**
   * Add `separator` between elements of the input. For example,
   * {{{
   * Process(1,2,3,4) |> intersperse(0) == Process(1,0,2,0,3,0,4)
   * }}}
   */
  def intersperse[A](separator: A): Process1[A, A] =
    await1[A].flatMap(head => emit(head) ++ id[A].flatMap(a => Process(separator, a)))

  /** Skip all but the last element of the input. */
  def last[I]: Process1[I, I] = {
    def go(prev: I): Process1[I, I] = receive1Or(emit(prev):Process1[I,I])(go)
    await1[I].flatMap(go)
  }

  /**
   * Skip all but the last element of the input.
   * This `Process` will always emit exactly one value;
   * If the input is empty, `i` is emitted.
   */
  def lastOr[I](li: => I): Process1[I, I] =
    receive1Or[I,I](emit(li))(i => lastOr(i) )

  /** Transform the input using the given function, `f`. */
  def lift[I, O](f: I => O): Process1[I, O] =
    id[I] map f

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
            case Halt(rsn) => out fby Halt(rsn)
            case other => out fby go(other)
          }
        case \/-(c) => emitO(c) fby go(curr)
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
      case Step(Await(_,rcv), cont) =>
        Await(L[I]: Env[I,Any]#Y[I],rcv) onHalt(rsn=> liftY(Halt(rsn) +: cont))

      case Step(emt@Emit(os), cont) =>
        emt onHalt(rsn=> liftY(Halt(rsn) +: cont))

      case hlt@Halt(rsn) => hlt
    }
  }

  /** Emits the greatest element of the input. */
  def maximum[A](implicit A: Order[A]): Process1[A,A] =
    reduce((x, y) => if (A.greaterThan(x, y)) x else y)

  /** Emits the element `a` of the input which yields the greatest value of `f(a)`. */
  def maximumBy[A,B: Order](f: A => B): Process1[A,A] =
    reduce((x, y) => if (Order.orderBy(f).greaterThan(x, y)) x else y)

  /** Emits the greatest value of `f(a)` for each element `a` of the input. */
  def maximumOf[A,B: Order](f: A => B): Process1[A,B] =
    lift(f) |> maximum

  /** Emits the smallest element of the input. */
  def minimum[A](implicit A: Order[A]): Process1[A,A] =
    reduce((x, y) => if (A.lessThan(x, y)) x else y)

  /** Emits the element `a` of the input which yields the smallest value of `f(a)`. */
  def minimumBy[A,B: Order](f: A => B): Process1[A,A] =
    reduce((x, y) => if (Order.orderBy(f).lessThan(x, y)) x else y)

  /** Emits the smallest value of `f(a)` for each element `a` of the input. */
  def minimumOf[A,B: Order](f: A => B): Process1[A,B] =
    lift(f) |> minimum

  /**
   * Split the input and send to either `chan1` or `chan2`, halting when
   * either branch halts.
   */
  def multiplex[I, I2, O](chan1: Process1[I, O], chan2: Process1[I2, O]): Process1[I \/ I2, O] =
    (liftL(chan1) pipe liftR(chan2)).map(_.fold(identity, identity))

  /**
   * Emits the sums of prefixes (running totals) of the input elements.
   * The first value emitted will always be zero.
   */
  def prefixSums[N](implicit N: Numeric[N]): Process1[N,N] =
    scan(N.zero)(N.plus)

  /**
   * `Process1` form of `List.reduce`.
   *
   * Reduces the elements of this Process using the specified associative binary operator.
   * {{{
   * Process(1,2,3,4) |> reduce(_ + _) == Process(10)
   * Process(1) |> reduce(_ + _) == Process(1)
   * Process() |> reduce(_ + _) == Process()
   * }}}
   *
   * Unlike `List.reduce` will not fail when Process is empty.
   */
  def reduce[A](f: (A, A) => A): Process1[A, A] =
    scan1(f).last

  /** Like `reduce` but uses Monoid `M` for associative operation. */
  def reduceMonoid[A](implicit M: Monoid[A]): Process1[A, A] =
    reduceSemigroup(M)

  /** Like `reduce` but uses Semigroup `M` for associative operation. */
  def reduceSemigroup[A](implicit M: Semigroup[A]): Process1[A, A] =
    reduce(M.append(_, _))

  /**
   * Like `reduce` only uses `f` to map `A` to `B` and uses Semigroup `M` for
   * associative operation.
   */
  def reduceMap[A, B](f: A => B)(implicit M: Semigroup[B]): Process1[A, B] =
    lift(f).reduceSemigroup(M)

  /**
   * Repartitions the input with the function `p`. On each step `p` is applied
   * to the input and all elements but the last of the resulting sequence
   * are emitted. The last element is then prepended to the next input using the
   * Semigroup `I`. For example,
   * {{{
   * Process("Hel", "l", "o Wor", "ld").repartition(_.split(" ").toIndexedSeq) ==
   *   Process("Hello", "World")
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
          case _ => emitAll(parts.init) fby go(Some(parts.last))
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
      receive1Or[I,I]( emitAll(carry.toList)) { i =>
        val next = carry.fold(i)(c => I.append(c, i))
        val (fst, snd) = p(next)
        fst.fold(go(snd))(head => emit(head) fby go(snd))
      }
    go(None)
  }

  /** Throws any input exceptions and passes along successful results. */
  def rethrow[A]: Process1[Throwable \/ A, A] =
    await1[Throwable \/ A].flatMap {
      case -\/(err) => throw err
      case \/-(a)   => emit(a)
    } repeat

  /**
   * Similar to List.scan.
   * Produces a process of `B` containing cumulative results of applying the operator to Process of `A`.
   * It will always emit `z`, even when the Process of `A` is empty
   */
  def scan[A, B](z: B)(f: (B, A) => B): Process1[A, B] =
    emit(z) fby await1[A].flatMap(a => scan(f(z, a))(f))

  /**
   * Like `scan` but uses Monoid for associative operation
   */
  def scanMonoid[A](implicit M: Monoid[A]): Process1[A, A] =
    scan(M.zero)(M.append(_, _))

  /**
   * Like `scan` only uses `f` to map `A` to `B` and uses Monoid `M` for associative operation
   */
  def scanMap[A, B](f: A => B)(implicit M: Monoid[B]): Process1[A, B] =
    lift(f).scanMonoid(M)

  /**
   * Similar to `scan`, but unlike it it won't emit the `z` even when there is no input of `A`.
   * {{{
   * Process(1,2,3,4) |> scan1(_ + _) == Process(1,3,6,10)
   * Process(1) |> scan1(_ + _) == Process(1)
   * Process() |> scan1(_ + _) == Process()
   * }}}
   */
  def scan1[A](f: (A, A) => A): Process1[A, A] = {
    def go(a: A): Process1[A, A] = emit(a) fby await1[A].flatMap(a2 => go(f(a, a2)))
    await1[A].flatMap(go)
  }

  /** Like `scan1` but uses Monoid `M` for associative operation. */
  def scan1Monoid[A](implicit M: Monoid[A]): Process1[A, A] =
    scanSemigroup(M)

  /** Like `scan1` but uses Semigroup `M` for associative operation. */
  def scanSemigroup[A](implicit M: Semigroup[A]): Process1[A, A] =
    scan1(M.append(_, _))

  /**
   * Like `scan1` only uses `f` to map `A` to `B` and uses Semigroup `M` for
   * associative operation.
   */
  def scan1Map[A, B](f: A => B)(implicit M: Semigroup[B]): Process1[A, B] =
    lift(f).scanSemigroup(M)

  /**
   * Emit the given values, then echo the rest of the input.
   */
  def shiftRight[I](head: I*): Process1[I, I] =
    emitAll(head) fby id

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any, Nothing] = await1[Any].flatMap(_ => halt)

  /**
   * Break the input into chunks where the delimiter matches the predicate.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def split[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I]): Process1[I, Vector[I]] =
      receive1Or[I, Vector[I]](emit(acc)) { i =>
        if (f(i)) emit(acc) fby go(Vector())
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
   * {{{
   * Process(1,2,-3,-4,5,6).splitWith(_ < 0).toList ==
   *   List(Vector(1,2), Vector(-3,-4), Vector(5,6))
   * }}}
   */
  def splitWith[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I], last: Boolean): Process1[I, Vector[I]] =
      receive1Or(emit(acc):Process1[I,Vector[I]])(i => {
         val cur = f(i)
         if (cur == last) go(acc :+ i, cur)
         else emit(acc) fby go(Vector(i), cur)
      })
    await1[I].flatMap(i => go(Vector(i), f(i)))
  }

  /** Remove any `None` inputs. */
  def stripNone[A]: Process1[Option[A], A] =
    collect { case Some(a) => a }

  /**
   * Emit a running sum of the values seen so far. The first value emitted will be the
   * first number seen (not `0`). The length of the output `Process` always matches the
   * length of the input `Process`.
   */
  def sum[N](implicit N: Numeric[N]): Process1[N, N] =
    reduce(N.plus)

  /** Passes through `n` elements of the input, then halts. */
  def take[I](n: Int): Process1[I, I] =
    if (n < 0) fail(new IllegalArgumentException(s"n must be > 0 is $n"))
    else if (n == 0) halt
    else await1[I] fby take(n - 1)

  /** Passes through elements of the input as long as the predicate is true, then halts. */
  def takeWhile[I](f: I => Boolean): Process1[I, I] =
    await1[I] flatMap (i => if (f(i)) emit(i) fby takeWhile(f) else halt)

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[I](f: I => Boolean): Process1[I, I] =
    await1[I] flatMap (i => if (f(i)) emit(i) fby takeThrough(f) else emit(i))

  /** Wraps all inputs in `Some`, then outputs a single `None` before halting. */
  def terminated[A]: Process1[A, Option[A]] =
     lift[A, Option[A]](Some(_)) onComplete emit(None)

  /**
   * Outputs a sliding window of size `n` onto the input.
   *
   * @throws IllegalArgumentException if `n` <= 0
   */
  def window[I](n: Int): Process1[I, Vector[I]] = {
    require(n > 0, "window size must be > 0, was: " + n)
    def go(acc: Vector[I], c: Int): Process1[I, Vector[I]] =
      if (c > 0) receive1Or[I,Vector[I]](emit(acc)) { i => go(acc :+ i, c - 1) }
      else  emit(acc) fby go(acc.tail, 1)
    go(Vector(), n)
  }

  /** Ungroups chunked input. */
  def unchunk[I]: Process1[Seq[I], I] =
    id[Seq[I]].flatMap(emitAll)

  /** A process which emits `(prev,cur)` pairs. */
  def zipPrevious[I](prev: I): Process1[I,(I,I)] =
    await1[I].flatMap(a => emit(prev -> a) fby zipPrevious(a))

  /** Zips the input with an index of type `Int`. */
  def zipWithIndex[A]: Process1[A,(A,Int)] =
    zipWithIndex[A,Int]

  /** Zips the input with an index of type `N`. */
  def zipWithIndex[A,N](implicit N: Numeric[N]): Process1[A,(A,N)] =
    zipWithState(N.zero)((_, n) => N.plus(n, N.one))

  /** Zips the input with state that begins with `z` and is updated by `next`. */
  def zipWithState[A,B](z: B)(next: (A, B) => B): Process1[A,(A,B)] = {
    def go(b: B): Process1[A,(A,B)] =
      await1[A].flatMap(a => emit((a, b)) fby go(next(a, b)))
    go(z)
  }


  object Await1 {
    /** deconstruct for `Await` directive of `Process1` **/
    def unapply[I, O](self: Process1[I, O]): Option[EarlyCause \/ I => Process1[I, O]] = self match {
      case Await(_, rcv) => Some((r:EarlyCause\/ I) => Try(rcv(r).run))
      case _             => None
    }

  }



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

  /** Alias for `this |> [[process1.drop]](n)`. */
  def drop(n: Int): Process[F,O] =
    this |> process1.drop(n)

  /** Alias for `this |> [[process1.dropLast]]`. */
  def dropLast: Process[F,O] =
    this |> process1.dropLast

  /** Alias for `this |> [[process1.dropLastIf]](p)`. */
  def dropLastIf(p: O => Boolean): Process[F,O] =
    this |> process1.dropLastIf(p)

  /** Alias for `this |> [[process1.dropWhile]](f)`. */
  def dropWhile(f: O => Boolean): Process[F,O] =
    this |> process1.dropWhile(f)

  /** Alias for `this |> [[process1.exists]](f)` */
  def exists(f: O => Boolean): Process[F,Boolean] =
    this |> process1.exists(f)

  /** Alias for `this |> [[process1.filter]](f)`. */
  def filter(f: O => Boolean): Process[F,O] =
    this |> process1.filter(f)

  /** Alias for `this |> [[process1.find]](f)` */
  def find(f: O => Boolean): Process[F,O] =
    this |> process1.find(f)

  /** Alias for `this |> [[process1.forall]](f)` */
  def forall(f: O => Boolean): Process[F,Boolean] =
    this |> process1.forall(f)

  /** Alias for `this |> [[process1.fold]](b)(f)`. */
  def fold[O2 >: O](b: O2)(f: (O2,O2) => O2): Process[F,O2] =
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

  /** Alias for `this |> [[process1.take]]` as take(1). */
  def once: Process[F,O] =
    this |> process1.take(1)

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
  def repartition[O2 >: O](p: O2 => collection.IndexedSeq[O2])(implicit S: Semigroup[O2]): Process[F,O2] =
    this |> process1.repartition(p)(S)

  /** Alias for `this |> [[process1.repartition2]](p)(S)` */
  def repartition2[O2 >: O](p: O2 => (Option[O2], Option[O2]))(implicit S: Semigroup[O2]): Process[F,O2] =
    this |> process1.repartition2(p)(S)

  /** Alias for `this |> [[process1.scan]](b)(f)`. */
  def scan[B](b: B)(f: (B,O) => B): Process[F,B] =
    this |> process1.scan(b)(f)

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

  /** Alias for `this |> [[process1.take]](n)`. */
  def take(n: Int): Process[F,O] =
    this |> process1.take(n)

  /** Alias for `this |> [[process1.takeThrough]](f)`. */
  def takeThrough(f: O => Boolean): Process[F,O] =
    this |> process1.takeThrough(f)

  /** Alias for `this |> [[process1.takeWhile]](f)`. */
  def takeWhile(f: O => Boolean): Process[F,O] =
    this |> process1.takeWhile(f)

  /** Alias for `this |> [[process1.terminated]]`. */
  def terminated: Process[F,Option[O]] =
    this |> process1.terminated

  /** Alias for `this |> [[process1.window]](n)`. */
  def window(n: Int): Process[F,Vector[O]] =
    this |> process1.window(n)

  /** Alias for `this |> [[process1.zipWithIndex[A]*]]. */
  def zipWithIndex: Process[F,(O,Int)] =
    this |> process1.zipWithIndex

  /** Alias for `this |> [[process1.zipWithIndex[A,N]*]]`. */
  def zipWithIndex[N: Numeric]: Process[F,(O,N)] =
    this |> process1.zipWithIndex[O,N]

  /** Alias for `this |> [[process1.zipWithState]](z)(next)`. */
  def zipWithState[B](z: B)(next: (O, B) => B): Process[F,(O,B)] =
    this |> process1.zipWithState(z)(next)
}
