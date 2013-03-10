package scalaz.stream

import Process._

trait wye {

  /** Nondeterministic version of `zipWith` which requests both sides in parallel. */
  def yipWith[I,I2,O](f: (I,I2) => O): Wye[I,I2,O] = 
    awaitBoth[I,I2].flatMap {
      case These.This(i) => awaitR[I2].flatMap(i2 => emit(f(i,i2)))  
      case These.That(i2) => awaitL[I].flatMap(i => emit(f(i,i2)))  
      case These.Both(i,i2) => emit(f(i,i2))
    }.repeat

  /** Nondeterministic version of `zip` which requests both sides in parallel. */
  def yip[I,I2]: Wye[I,I2,(I,I2)] = yipWith((_,_))
}

object wye extends wye
