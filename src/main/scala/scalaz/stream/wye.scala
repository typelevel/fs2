package scalaz.stream

import collection.immutable.Queue
import concurrent.duration._

import scalaz.{\/, -\/, \/-}
import Process._

trait wye {

  /** 
   * A `Wye` which emits values from its right branch, but allows up to `n`
   * elements from the left branch to enqueue unanswered before blocking
   * on the right branch.
   */
  def boundedQueue[I](n: Int): Wye[Any,I,I] = 
    yipWithL(n)((i,i2) => i2) 

  /** 
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def either[I,I2]: Wye[I,I2,I \/ I2] = 
    merge[I \/ I2].contramapL((i: I) => -\/(i)).
                   contramapR((i2: I2) => \/-(i2))

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

  /** 
   * A `Wye` which blocks on the right side when either a) the age of the oldest unanswered 
   * element from the left size exceeds the given duration, or b) the number of unanswered 
   * elements from the left exceeds `maxSize`.
   */
  def timedQueue[I](d: Duration, maxSize: Int = Int.MaxValue): Wye[Duration,I,I] = {
    def go(q: Vector[Duration]): Wye[Duration,I,I] = 
      awaitBoth[Duration,I].flatMap {
        case These.This(d2) => 
          if (q.size >= maxSize || (d2 - q.headOption.getOrElse(d2) > d))
            awaitR[I].flatMap(i => emit(i) then go(q.drop(1)))  
          else
            go(q :+ d2)
        case These.That(i) => emit(i) then (go(q.drop(1)))
        case These.Both(t,i) => emit(i) then (go(q.drop(1) :+ t))
      }
    go(Vector())
  }

  /** 
   * `Wye` which repeatedly awaits both branches, emitting any values
   * received from the right. Useful in conjunction with `connect`,
   * for instance `src.connect(snk)(unboundedQueue)`
   */
  def unboundedQueue[I]: Wye[Any,I,I] = 
    awaitBoth[Any,I].flatMap {
      case These.This(any) => halt
      case These.That(i) => emit(i) then unboundedQueue
      case These.Both(_,i) => emit(i) then unboundedQueue
    }

  /** Nondeterministic version of `zip` which requests both sides in parallel. */
  def yip[I,I2]: Wye[I,I2,(I,I2)] = yipWith((_,_))

  /** 
   * Left-biased, buffered version of `yip`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left. 
   */ 
  def yipL[I,I2](n: Int): Wye[I,I2,(I,I2)] = 
    yipWithL(n)((_,_))

  /** Nondeterministic version of `zipWith` which requests both sides in parallel. */
  def yipWith[I,I2,O](f: (I,I2) => O): Wye[I,I2,O] = 
    awaitBoth[I,I2].flatMap {
      case These.This(i) => awaitR[I2].flatMap(i2 => emit(f(i,i2)))  
      case These.That(i2) => awaitL[I].flatMap(i => emit(f(i,i2)))  
      case These.Both(i,i2) => emit(f(i,i2))
    }.repeat

  /** 
   * Left-biased, buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left. 
   */ 
  def yipWithL[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    def go(buf: Vector[I]): Wye[I,O,O2] =
      if (buf.size > n) awaitR[O].flatMap { o => 
        emit(f(buf.head,o)) ++ go(buf.tail)
      }
      else if (buf.isEmpty) awaitL[I].flatMap { i => go(buf :+ i) }
      else awaitBoth[I,O].flatMap {
        case These.This(i) => go(buf :+ i)
        case These.That(o) => emit(f(buf.head,o)) ++ go(buf.tail)
        case These.Both(i,o) => emit(f(buf.head,o)) ++ go(buf :+ i) 
      }
    go(Vector())
  }
}

object wye extends wye
