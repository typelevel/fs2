package scalaz.stream

import scala.collection.immutable.Vector

import Process._

trait process1 {

  /** Skips the first `n` elements of the input, then passes through the rest. */
  def drop[I](n: Int): Process1[I,I] = 
    skip.replicateM_(n).drain ++ id[I]

  /** 
   * Skips elements of the input while the predicate is true, 
   * then passes through the remaining inputs. 
   */
  def dropWhile[I](f: I => Boolean): Process1[I,I] = 
    await1[I] flatMap (i => if (f(i)) dropWhile(f) else id)

  /** Skips any elements of the input not matching the predicate. */
  def filter[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) emit(i) else Halt) repeat

  /** Repeatedly echo the input; satisfies `x |> id == x` and `id |> x == x`. */
  def id[I]: Process1[I,I] = 
    await1[I].repeat

  /** Transform the input using the given function, `f`. */
  def lift[I,O](f: I => O): Process1[I,O] = 
    id[I] map f
  
  /** Passes through `n` elements of the input, then halts. */
  def take[I](n: Int): Process1[I,I] = 
    if (n <= 0) Halt
    else await1[I] ++ take(n-1)

  /** Passes through elements of the input as long as the predicate is true, then halts. */
  def takeWhile[I](f: I => Boolean): Process1[I,I] = 
    await1[I] flatMap (i => if (f(i)) emit(i) ++ takeWhile(f) else Halt)

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any,Nothing] = await1[Any].flatMap(_ => Halt) 

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
        val cur = f(i)
        if (!cur && last) emit(acc) ++ go(Vector(i), false)
        else go(acc :+ i, cur)
      } orElse (emit(acc)) 
    go(Vector(), false)
  }

  /** Behaves like the identity process, but requests `n` elements at a time from its input. */
  def buffer[I](n: Int): Process1[I,I] =
    chunk[I](n).flatMap(emitAll)

  /** 
   * Behaves like the identity process, but requests elements from its 
   * input in blocks that end whenever the predicate switches from true to false.
   */
  def bufferBy[I](f: I => Boolean): Process1[I,I] =
    chunkBy(f).flatMap(emitAll)
}

object process1 extends process1
