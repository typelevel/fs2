package scalaz.stream

import scala.collection.immutable.Vector

import scalaz.{\/, -\/, \/-}

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
    await1[I] flatMap (i => if (f(i)) emit(i) else Halt) repeat

  /** Repeatedly echo the input; satisfies `x |> id == x` and `id |> x == x`. */
  def id[I]: Process1[I,I] = 
    await1[I].repeat

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
  
  /** Passes through `n` elements of the input, then halts. */
  def take[I](n: Int): Process1[I,I] = 
    if (n <= 0) Halt
    else await1[I] then take(n-1)

  /** Passes through elements of the input as long as the predicate is true, then halts. */
  def takeWhile[I](f: I => Boolean): Process1[I,I] = 
    await1[I] flatMap (i => if (f(i)) emit(i) then takeWhile(f) else Halt)

  /** Throws any input exceptions and passes along successful results. */
  def rethrow[A]: Process1[Throwable \/ A, A] = 
    await1[Throwable \/ A].flatMap { 
      case -\/(err) => throw err
      case \/-(a) => emit(a)
    } repeat

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any,Nothing] = await1[Any].flatMap(_ => Halt) 

  /** 
   * Emit a running sum of the values seen so far. The first value emitted will be the 
   * first number seen (not `0`). The length of the output `Process` always matches the 
   * length of the input `Process`. 
   */
  def sum[N](implicit N: Numeric[N]): Process1[N,N] = {
    def go(acc: N): Process1[N,N] = await1[N].flatMap { n => 
      val acc2 = N.plus(acc, n)
      emit(acc2) ++ go(acc2)
    } 
    go(N.zero)
  }

}

object process1 extends process1
