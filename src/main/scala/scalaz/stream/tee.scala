package scalaz.stream

import Process._

/** 
 * Module of various `Tee` processes.
 */
trait tee {
  
  /** Before reading from the `right`, checks that the left branch is `true`. */
  def guard[I]: Tee[Boolean,I,I] = 
    awaitL[Boolean].flatMap(ok => if (ok) awaitR[I] then guard else guard)
    
  /** A `Tee` which alternates between emitting values from the left input and the right input. */
  def interleave[I]: Tee[I,I,I] = repeat { for {
    i1 <- awaitL[I]
    i2 <- awaitR[I]
    r <- emit(i1) ++ emit(i2)
  } yield r }

  /** A `Tee` which ignores all input from left. */
  def passR[I2]: Tee[Any,I2,I2] = awaitR[I2].repeat
  
  /* A `Tee` which ignores all input from the right. */
  def passL[I]: Tee[I,Any,I] = awaitL[I].repeat

  /** Defined as `zipWith((_,_))` */
  def zip[I,I2]: Tee[I,I2,(I,I2)] = zipWith((_,_))

  /** A version of `zip` that pads the shorter stream with values. */
  def zipAll[I,I2](padI: I, padI2: I2): Tee[I,I2,(I,I2)] = 
    zipWithAll(padI, padI2)((_,_))
  
  /** 
   * Zip together two inputs, then apply the given function,
   * halting as soon as either input is exhausted. 
   * This implementation reads from the left, then the right. 
   */
  def zipWith[I,I2,O](f: (I,I2) => O): Tee[I,I2,O] = { for {
    i <- awaitL[I]
    i2 <- awaitR[I2]
    r <- emit(f(i,i2))
  } yield r } repeat


  /** A version of `zipWith` that pads the shorter stream with values. */
  def zipWithAll[I,I2,O](padI: I, padI2: I2)(
                         f: (I,I2) => O): Tee[I,I2,O] = {
    val fbR = passR[I2] map (f(padI, _    ))
    val fbL = passL[I]  map (f(_   , padI2))
    receiveLOr(fbR: Tee[I,I2,O])(i => 
    receiveROr(fbL: Tee[I,I2,O])(i2 => emit(f(i,i2)))) repeat
  }
}

object tee extends tee
