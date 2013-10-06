package scalaz.stream

import collection.immutable.Vector
import java.nio.charset.Charset

import scalaz.{\/, -\/, \/-, Monoid, Semigroup, Equal}
import scalaz.\/._
import scalaz.syntax.equal._

import Process._
import scalaz.stream.processes._
import scalaz.stream.Process.Emit
import scala.Some
import scalaz.stream.Process.Halt

trait process1 {

  // nb: methods are in alphabetical order, there are going to be so many that
  // any other order will just going get confusing

  /** Await a single value, returning `None` if the input has been exhausted. */
  def awaitOption[I]: Process1[I,Option[I]] =
    await1[I].map(Some(_)).orElse(emit(None))

  /** Behaves like the identity process, but requests `n` elements at a time from its input. */
  def buffer[I](n: Int): Process1[I,I] =
    chunk[I](n).flatMap(emitAll)

  /**
   * Behaves like the identity process, but requests elements from its
   * input in blocks that end whenever the predicate switches from true to false.
   */
  def bufferBy[I](f: I => Boolean): Process1[I,I] =
    chunkBy(f).flatMap(emitAll)

  /** Behaves like the identity process, but batches all output into a single `Emit`. */
  def bufferAll[I]: Process1[I,I] =
    chunkAll[I].flatMap(emitAll)

  /**
   * Groups inputs into chunks of size `n`. The last chunk may have size
   * less then `n`, depending on the number of elements in the input.
   */
  def chunk[I](n: Int): Process1[I,Vector[I]] = {
    def go(m: Int, acc: Vector[I]): Process1[I,Vector[I]] =
      if (m <= 0) emit(acc) ++ go(n, Vector())
      else await1[I].flatMap(i => go(m-1, acc :+ i)).orElse(emit(acc))
    if (n <= 0) sys.error("chunk size must be > 0, was: " + n)
    go(n, Vector())
  }

  /**
   * Like `chunk`, but emits a chunk whenever the predicate switches from
   * true to false.
   */
  def chunkBy[I](f: I => Boolean): Process1[I,Vector[I]] = {
    def go(acc: Vector[I], last: Boolean): Process1[I,Vector[I]] =
      await1[I].flatMap { i =>
        val chunk = acc :+ i
        val cur = f(i)
        if (!cur && last) emit(chunk) fby go(Vector(), false)
        else go(chunk, cur)
      } orElse (emit(acc))
    go(Vector(), false)
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
  def collect[I,I2](pf: PartialFunction[I,I2]): Process1[I,I2] =
    id[I].flatMap(pf andThen(emit) orElse { case _ => halt })

  /** 
   * Emits a single `true` value if all input matches the predicate.
   * Halts with `false` as soon as a non-matching element is received. 
   */
  def forall[I](f: I => Boolean): Process1[I,Boolean] = 
    await1[I].flatMap(i => if (f(i)) forall(f) else emit(false)) orElse (emit(true))

  /**
   * Emit the given values, then echo the rest of the input. This is
   * useful for feeding values incrementally to some other `Process1`:
   * `init(1,2,3) |> p` returns a version of `p` which has been fed
   * `1, 2, 3`.
   */
  def init[I](head: I*): Process1[I,I] =
    emitSeq(head) ++ id

  /**
   * Transform `p` to operate on the left hand side of an `\/`, passing
   * through any values it receives on the right. Note that this halts
   * whenever `p` halts.
   */
  def liftL[A,B,C](p: Process1[A,B]): Process1[A \/ C, B \/ C] =
    p match {
      case h@Halt(_) => h
      case Emit(h, t) => Emit(h map left, liftL(t))
      case _ => await1[A \/ C].flatMap {
        case -\/(a) => liftL(feed1(a)(p))
        case \/-(c) => emit(right(c)) ++ liftL(p)
      }
    }

  /**
   * Transform `p` to operate on the right hand side of an `\/`, passing
   * through any values it receives on the left. Note that this halts
   * whenever `p` halts.
   */
  def liftR[A,B,C](p: Process1[B,C]): Process1[A \/ B, A \/ C] =
    lift((e: A \/ B) => e.swap) |> liftL(p).map(_.swap)

  /**
   * Split the input and send to either `chan1` or `chan2`, halting when
   * either branch halts.
   */
  def multiplex[I,I2,O](chan1: Process1[I,O], chan2: Process1[I2,O]): Process1[I \/ I2, O] =
    (liftL(chan1) pipe liftR(chan2)).map(_.fold(identity, identity))

  /** Feed a single input to a `Process1`. */
  def feed1[I,O](i: I)(p: Process1[I,O]): Process1[I,O] =
    p match {
      case h@Halt(_) => h
      case Emit(h, t) => emitSeq(h, feed1(i)(t))
      case Await1(recv,fb,c) =>
        try recv(i)
        catch {
          case End => fb
          case e: Throwable => c.causedBy(e)
        }
    }

  /** Feed a sequence of inputs to a `Process1`. */
  def feed[I,O](i: Seq[I])(p: Process1[I,O]): Process1[I,O] =
    p match {
      case Halt(_) => p
      case Emit(h, t) => Emit(h, feed(i)(t))
      case _ =>
        var buf = i
        var cur = p
        var ok = true
        while (!buf.isEmpty && ok) {
          val h = buf.head
          buf = buf.tail
          cur = feed1(h)(cur)
          cur match {
            case Halt(_)|Emit(_,_) => ok = false
            case _ => ()
          }
        }
        if (buf.isEmpty) cur
        else feed(buf)(cur)
    }

  /**
   * Record evaluation of `p`, emitting the current state along with the ouput of each step.
   */
  def record[I,O](p: Process1[I,O]): Process1[I,(Seq[O], Process1[I,O])] = p match {
    case h@Halt(_) => h
    case Emit(h, t) => Emit(Seq((h, p)), record(t))
    case Await1(recv, fb, c) =>
      Emit(Seq((List(), p)), await1[I].flatMap(recv andThen (record[I,O])).orElse(record(fb),record(c)))
  }

  /**
   * Break the input into chunks where the delimiter matches the predicate.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def split[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I]): Process1[I, Vector[I]] =
      await1[I].flatMap { i =>
        if (f(i)) emit(acc) fby go(Vector())
        else go(acc :+ i)
      } orElse (emit(acc))
    go(Vector())
  }

  /**
   * Break the input into chunks where the input is equal to the given delimiter.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def splitOn[I:Equal](i: I): Process1[I, Vector[I]] =
    split(_ === i)

  /** Collects up all output of this `Process1` into a single `Emit`. */
  def chunkAll[I]: Process1[I,Vector[I]] =
    chunkBy[I](_ => false)

  /** Outputs a sliding window of size `n` onto the input. */
  def window[I](n: Int): Process1[I,Vector[I]] = {
    def go(acc: Vector[I], c: Int): Process1[I,Vector[I]] =
      if (c > 0)
        await1[I].flatMap { i => go(acc :+ i, c - 1) } orElse emit(acc)
      else
        emit(acc) fby go(acc.tail, 1)
    go(Vector(), n)
  }

  /** Skips the first `n` elements of the input, then passes through the rest. */
  def drop[I](n: Int): Process1[I,I] =
    if (n <= 0) id[I]
    else skip fby drop(n-1)

  /**
   * Skips elements of the input while the predicate is true,
   * then passes through the remaining inputs.
   */
  def dropWhile[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) dropWhile(f) else emit(i) fby id)

  /** Skips any elements of the input not matching the predicate. */
  def filter[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) emit(i) else halt) repeat

  /** 
   * Skips any elements not satisfying predicate and when found, will emit that 
   * element and terminate
   */
  def find[I](f: I => Boolean): Process1[I,I] =  
    await1[I] flatMap (i => if(f(i)) emit(i) else find(f))
  
  /**
   * `Process1` form of `List.fold`. 
   *  Folds the elements of this Process using the specified associative binary operator.
   * 
   *  Unlike List.fold the order is always from the `left` side, i.e. it will always 
   *  honor order of `A`.
   *  
   *  If Process of `A` is empty, it will just emit `z` and terminate
   *  
   *  `Process(1,2,3,4) |> fold(0)(_ + _) == Process(10)`
   */
  def fold[A,B](z: B)(f: (B,A) => B): Process1[A,B] = 
    scan(z)(f).last

  /**
   * Like `fold` but uses Monoid for folding operation 
   */
  def foldMonoid[A](implicit M: Monoid[A]): Process1[A,A] =
    fold(M.zero)(M.append(_,_))
  
  /**
   * Like `fold` only uses `f` to map `A` to `B` and uses Monoid `M` or associative operation
   */
  def foldMap[A,B](f: A => B)(implicit M: Monoid[B]): Process1[A,B] = 
   id[A].map(f).foldMonoid(M)

  /**
   * `Process1` form of `List.reduce`.
   *
   *  Reduces the elements of this Process using the specified associative binary operator.
   *
   *  `Process(1,2,3,4) |> reduce(_ + _) == Process(10)`
   *  `Process(1) |> reduce(_ + _) == Process(1)`
   *  `Process() |> reduce(_ + _) == Process()`
   *
   */
  def fold1[A](f: (A,A) => A): Process1[A,A] = 
    reduce(f)

  /** alias for `reduceMonoid` */
  def fold1Monoid[A](implicit M: Semigroup[A]): Process1[A,A] =
    reduce(M.append(_,_))

  /** alias for `reduceSemigroup` */
  def foldSemigroup[A](implicit M: Semigroup[A]): Process1[A,A] =
    reduce(M.append(_,_))

  /**
   * Like `fold1` only uses `f` to map `A` to `B` and uses Monoid `M` or associative operation
   */
  def fold1Map[A,B](f: A => B)(implicit M: Monoid[B]): Process1[A,B] =
    reduceMap(f)(M)

  /**
   * `Process1` form of `List.reduce`.
   * 
   *  Reduces the elements of this Process using the specified associative binary operator.
   * 
   *  `Process(1,2,3,4) |> reduce(_ + _) == Process(10)`
   *  `Process(1) |> reduce(_ + _) == Process(1)`
   *  `Process() |> reduce(_ + _) == Process()`
   *  
   *  Unlike `List.reduce` will not fail when Process is empty.
   *   
   */
  def reduce[A](f: (A,A) => A): Process1[A,A] = 
    scan1(f).last

  /**
   * Like `reduce` but uses Monoid for reduce operation
   */
  def reduceMonoid[A](implicit M: Semigroup[A]): Process1[A,A] =
    reduce(M.append(_,_))

  /**
   * Like `reduce` but uses Semigroup associative operation
   */
  def reduceSemigroup[A](implicit M: Semigroup[A]): Process1[A,A] =
    reduce(M.append(_,_))

  /**
   * Like `reduce` only uses `f` to map `A` to `B` and uses Monoid `M` or associative operation
   */
  def reduceMap[A,B](f: A => B)(implicit M: Monoid[B]): Process1[A,B] =
    id[A].map(f).reduceMonoid(M)

  /** Repeatedly echo the input; satisfies `x |> id == x` and `id |> x == x`. */
  def id[I]: Process1[I,I] =
    await1[I].repeat

  /**
   * Add `separator` between elements of the input. For example,
   * `Process(1,2,3,4) |> intersperse(0) == Process(1,0,2,0,3,0,4)`.
   */
  def intersperse[A](separator: A): Process1[A,A] =
    await1[A].flatMap(head => emit(head) ++ id[A].flatMap(a => Process(separator, a)))

  /** Skip all but the last element of the input. */
  def last[I]: Process1[I,I] = {
    def go(prev: I): Process1[I,I] =
      awaitOption[I].flatMap {
        case None => emit(prev)
        case Some(prev2) => go(prev2)
      }
    await1[I].flatMap(go)
  }

  /** Transform the input using the given function, `f`. */
  def lift[I,O](f: I => O): Process1[I,O] =
    id[I] map f

  /** 
   * Similar to List.scan. 
   * Produces a process of `B` containing cumulative results of applying the operator to Process of `A`.
   * It will always emit `z`, even when the Process of `A` is empty
   */
  def scan[A,B](z:B)(f:(B,A) => B) : Process1[A,B] =
    emit(z) fby await1[A].flatMap (a => scan(f(z,a))(f))

  /**
   * Like `scan` but uses Monoid for associative operation 
   */
  def scanMonoid[A](implicit M: Monoid[A]): Process1[A,A] =
    scan(M.zero)(M.append(_,_))

  /**
   * Like `scan` only uses `f` to map `A` to `B` and uses Monoid `M` or associative operation
   */
  def scanMap[A,B](f:A => B)(implicit M: Monoid[B]): Process1[A,B] =
    id[A].map(f).scanMonoid(M)

  /**
   * Similar to `scan`, but unlike it it won't emit the `z` even when there is no input of `A`.
   *
   *  `Process(1,2,3,4) |> scan1(_ + _) == Process(1,3,6,10)`
   *  `Process(1) |> scan1(_ + _) == Process(1)`
   *  `Process() |> scan1(_ + _) == Process()`
   * 
   */
  def scan1[A](f: (A,A) => A): Process1[A,A] = {
    def go(a: A): Process1[A,A] = emit(a) fby await1[A].flatMap(a2 => go(f(a,a2)))
    await1[A].flatMap(go)
  }

  /**
   * Like `scan1` but uses Monoid for associative operation 
   */
  def scan1Monoid[A](implicit M: Monoid[A]): Process1[A,A] =
    scan1(M.append(_,_))

  /**
   * Like `scan1` but uses Semigroup for associative operation 
   */
  def scanSemigroup[A](implicit M: Semigroup[A]): Process1[A,A] =
    scan1(M.append(_,_))

  /**
   * Like `scan1` only uses `f` to map `A` to `B` and uses Monoid `M` or associative operation
   */
  def scan1Map[A,B](f:A => B)(implicit M: Monoid[B]): Process1[A,B] =
    id[A].map(f).scan1Monoid(M)
  
  /** Wraps all inputs in `Some`, then outputs a single `None` before halting. */
  def terminated[A]: Process1[A,Option[A]] =
    lift[A,Option[A]](Some(_)) ++ emit(None)

  /** Passes through `n` elements of the input, then halts. */
  def take[I](n: Int): Process1[I,I] =
    if (n <= 0) halt
    else await1[I] fby take(n-1)

  /** Passes through elements of the input as long as the predicate is true, then halts. */
  def takeWhile[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) emit(i) fby takeWhile(f) else halt)

  /** Throws any input exceptions and passes along successful results. */
  def rethrow[A]: Process1[Throwable \/ A, A] =
    await1[Throwable \/ A].flatMap {
      case -\/(err) => throw err
      case \/-(a) => emit(a)
    } repeat

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any,Nothing] = await1[Any].flatMap(_ => halt)

  /** Remove any `None` inputs. */
  def stripNone[A]: Process1[Option[A],A] =
    await1[Option[A]].flatMap {
      case None => stripNone
      case Some(a) => emit(a) ++ stripNone
    }

  /**
   * Emit a running sum of the values seen so far. The first value emitted will be the
   * first number seen (not `0`). The length of the output `Process` always matches the
   * length of the input `Process`.
   */
  def sum[N](implicit N: Numeric[N]): Process1[N,N] =
    reduce(N.plus)

  private val utf8Charset = Charset.forName("UTF-8")

  /** Convert `String` inputs to UTF-8 encoded byte arrays. */
  val utf8Encode: Process1[String,Array[Byte]] =
    lift(_.getBytes(utf8Charset))
}

object process1 extends process1
