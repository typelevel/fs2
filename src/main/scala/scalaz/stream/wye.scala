package scalaz.stream

import collection.immutable.Queue

import scalaz.{\/, -\/, \/-}
import Process._

trait wye {

  def boundedQueue[I](n: Int): Wye[Any,I,I] = 
    byipWithL(n)((i,i2) => i2) 

  /** 
   * Left-biased, buffered version of `yip`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left. 
   */ 
  def byipL[I,I2](n: Int): Wye[I,I2,(I,I2)] = 
    byipWithL(n)((_,_))

  /** 
   * Left-biased, buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left. 
   */ 
  def byipWithL[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    // todo: switch to vector, or track size
    def go(buf: Queue[I]): Wye[I,O,O2] =
      if (buf.size > n) awaitR[O].flatMap { o => 
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

  /** 
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def merge[I]: Wye[I,I,I] = {
    def go(biasL: Boolean): Wye[I,I,I] = 
      receiveBoth[I,I,I]({
        case These.This(i) => emit(i) then (go(!biasL))
        case These.That(i) => emit(i) then (go(!biasL))
        case These.Both(i,i2) => 
          if (biasL) emitSeq(List(i,i2)) then (go(!biasL)) 
          else       emitSeq(List(i2,i)) then (go(!biasL)) 
      } 
    )
    go(true)
  }

  def either[I,I2]: Wye[I,I2,I \/ I2] = 
    merge[I \/ I2].contramapL((i: I) => -\/(i)).
                   contramapR((i2: I2) => \/-(i2))

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
