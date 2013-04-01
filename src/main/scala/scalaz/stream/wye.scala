package scalaz.stream

import collection.immutable.Queue

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

  /** 
   * Buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. 
   */ 
  def byipWith[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    def go(buf: Queue[I]): Wye[I,O,O2] =
      if (buf.size > n ) awaitR[O].flatMap { o => 
        val (i,buf2) = buf.dequeue
        emit(f(i,o)) ++ go(buf2)
      } 
      else if (buf.isEmpty) awaitL[I].flatMap { i => go(buf.enqueue(i)) }
      else awaitBoth[I,O].flatMap {
        case These.This(i) => go(buf.enqueue(i))
        case These.That(o) => 
          val (i, buf2) = buf.dequeue
          emit(f(i,o)) ++ go(buf2)
        case These.Both(inew,o) => 
          val (i, buf2) = buf.dequeue
          emit(f(i,o)) ++ go(buf.enqueue(inew)) 
      }
    go(Queue())
  }
}

object wye extends wye
