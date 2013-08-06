package scalaz.stream

import scalaz._
import scalaz.concurrent.{Actor, Strategy, Task}

import QueueMsg._

import collection.immutable.Queue

trait actor {

  /** 
   * Returns a `Process` stream that can be added to or halted 
   * asynchronously using the returned `Actor`. The output
   * stream will terminate when the actor receives `QueueMsg.Close()`
   * or `QueueMsg.Fail(e)`. The memory usage of the actor can 
   * grow unbounded if `QueueMsg.Enqueue` messages are sent to the
   * actor faster than they are dequeued by whatever consumes the output `Process`.
   * 
   * Use the `QueueMsg.QueueSize` message to asynchronously check the
   * queue size and throttle whatever is feeding the actor messages.
   */
  def queue[A](implicit S: Strategy): Task[(Actor[QueueMsg[A]], Process[Task, A])] = Task.delay {
    var q = Queue[Throwable \/ A]()
    var n = 0 // size of q
    var listeners: Queue[(Throwable \/ A) => Unit] = Queue()
    val a: Actor[QueueMsg[A]] = Actor.actor {
      case Close() => 
        q = Queue(-\/(Process.End)) 
        n = 1
      case Fail(e) => 
        q = Queue(-\/(e))
        n = 1
      case Enqueue(a) =>
        if (listeners.isEmpty) { q = q.enqueue(\/-(a)); n += 1 }
        else { 
          val (cb, l2) = listeners.dequeue 
          listeners = l2
          cb(\/-(a))
        }
      case Dequeue(cb) => 
        if (q.isEmpty) listeners = listeners.enqueue(cb)
        else {
          val (a, q2) = q.dequeue
          q = q2
          n -= 1
          cb(a)
        }
      case QueueSize(cb) => cb(n)
    }
    val p = Process.repeatWrap { Task.async[A] { cb => a ! Dequeue(cb) } }
    (a, p)
  }

  /** Convert an `Actor[A]` to a `Sink[Task, A]`. */
  def toSink[A](snk: Actor[A]): Process[Task, A => Task[Unit]] = 
    Process.repeatWrap { Task.now { (a: A) => Task.delay { snk ! a } } }
}

object actor extends actor

trait QueueMsg[A]

object QueueMsg {
  case class Dequeue[A](callback: (Throwable \/ A) => Unit) extends QueueMsg[A] 
  case class Enqueue[A](a: A) extends QueueMsg[A]
  case class Fail[A](error: Throwable) extends QueueMsg[A]
  case class Close[A]() extends QueueMsg[A]
  case class QueueSize[A](callback: Int => Unit) extends QueueMsg[A]

  def enqueue[A](a: A): QueueMsg[A] =
    Enqueue(a)

  def dequeue[A](cb: A => Unit, onError: Throwable => Unit = t => ()): QueueMsg[A] = 
    Dequeue {
      case -\/(e) => onError(e)
      case \/-(a) => cb(a)
    }
  
  def close[A]: QueueMsg[A] = Close[A]()
  def fail[A](err: Throwable): QueueMsg[A] = Fail(err)
}
