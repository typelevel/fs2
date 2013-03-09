package scalaz.stream

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
  
  /** Passes through `n` elements of the input, then halt. */
  def take[I](n: Int): Process1[I,I] = 
    if (n <= 0) Halt
    else await1[I] ++ take(n-1)

  /** Passes through elements of the input as long as the predicate is true, then halt. */
  def takeWhile[I](f: I => Boolean): Process1[I,I] = 
    await1[I] flatMap (i => if (f(i)) emit(i) ++ takeWhile(f) else Halt)

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any,Nothing] = await1[Any].flatMap(_ => Halt) 
}

object process1 extends process1
