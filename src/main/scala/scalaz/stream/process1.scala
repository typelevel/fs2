package scalaz.stream

import collection.immutable.Vector
import java.nio.charset.Charset

import scalaz.{\/, -\/, \/-, Monoid, Semigroup, Equal}
import scalaz.\/._
import scalaz.syntax.equal._

import Process._

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
        if (!cur && last) emit(chunk) then go(Vector(), false)
        else go(chunk, cur)
      } orElse (emit(acc))
    go(Vector(), false)
  }

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
        case -\/(a) => liftL(init(a) pipe p)  
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

  /**
   * Break the input into chunks where the delimiter matches the predicate.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def split[I](f: I => Boolean): Process1[I, Vector[I]] = {
    def go(acc: Vector[I]): Process1[I, Vector[I]] =
      await1[I].flatMap { i =>
        if (f(i)) emit(acc) then go(Vector())
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

  /** Skips the first `n` elements of the input, then passes through the rest. */
  def drop[I](n: Int): Process1[I,I] =
    if (n <= 0) id[I]
    else skip then drop(n-1)

  /**
   * Skips elements of the input while the predicate is true,
   * then passes through the remaining inputs.
   */
  def dropWhile[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) dropWhile(f) else emit(i) then id)

  /** Skips any elements of the input not matching the predicate. */
  def filter[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) emit(i) else halt) repeat

  /**
   * `Process1` form of `List.scanLeft`. Like `List.scanLeft`, this
   * always emits at least the given `b`, even if the input is empty.
   */
  def fold[A,B](b: B)(f: (B,A) => B): Process1[A,B] =
    emit(b) then await1[A].flatMap { a => fold(f(b,a))(f) }

  /**
   * Like `fold`, but emits values starting with the first element it
   * receives. If the input is empty, this emits no values.
   */
  def fold1[A](f: (A,A) => A): Process1[A,A] = {
    def go(a: A): Process1[A,A] =
      emit(a) then await1[A].flatMap(a2 => go(f(a,a2)))
    await1[A].flatMap(go)
  }

  /**
   * Emits the `Monoid` identity, followed by a running total
   * of the values seen so far, using the `Monoid` operation:
   *
   * `Process(1,2,3,4) |> fromMonoid(sumMonoid) == Process(0,1,3,6,10)`
   */
  def fromMonoid[A](implicit M: Monoid[A]): Process1[A,A] =
    fold(M.zero)((a,a2) => M.append(a,a2))

  /**
   * Alias for `fromSemigroup`. Starts emitting when it receives
   * its first value from the input.
   */
  def fromMonoid1[A](implicit M: Semigroup[A]): Process1[A,A] =
    fromSemigroup[A]

  /**
   * Emits the sum of the elements seen so far, using the
   * semigroup's operation, starting with the first element
   * of the input sequence. If the input is empty, emits
   * no values.
   *
   * `Process(1,2,3,4) |> fromSemigroup(sumMonoid) == Process(1,3,6,10)`
   */
  def fromSemigroup[A](implicit M: Semigroup[A]): Process1[A,A] =
    fold1((a,a2) => M.append(a,a2))

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

  /** Wraps all inputs in `Some`, then outputs a single `None` before halting. */
  def terminated[A]: Process1[A,Option[A]] =
    lift[A,Option[A]](Some(_)) ++ emit(None)

  /** Passes through `n` elements of the input, then halts. */
  def take[I](n: Int): Process1[I,I] =
    if (n <= 0) halt
    else await1[I] then take(n-1)

  /** Passes through elements of the input as long as the predicate is true, then halts. */
  def takeWhile[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) emit(i) then takeWhile(f) else halt)

  /** Throws any input exceptions and passes along successful results. */
  def rethrow[A]: Process1[Throwable \/ A, A] =
    await1[Throwable \/ A].flatMap {
      case -\/(err) => throw err
      case \/-(a) => emit(a)
    } repeat

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any,Nothing] = await1[Any].flatMap(_ => halt)

  /**
   * Emit a running sum of the values seen so far. The first value emitted will be the
   * first number seen (not `0`). The length of the output `Process` always matches the
   * length of the input `Process`.
   */
  def sum[N](implicit N: Numeric[N]): Process1[N,N] =
    fold1(N.plus)

  private val utf8Charset = Charset.forName("UTF-8")

  /** Convert `String` inputs to UTF-8 encoded byte arrays. */
  val utf8Encode: Process1[String,Array[Byte]] =
    lift(_.getBytes(utf8Charset))
}

object process1 extends process1
