package scalaz.stream

import scalaz._
import scalaz.concurrent.{Actor, Strategy, Task}

import collection.immutable.Queue

trait actor {

  /** 
   * Returns a discrete `Process` stream that can be added to or 
   * halted asynchronously by sending the returned `Actor` messages.
   * `message.queue.enqueue(a)` adds an element to the stream in FIFO order, 
   * `message.queue.close` terminates the stream, 
   * `message.queue.cancel` terminates the stream immediately, ignoring queued messages,
   * `message.queue.fail(e)` terminates the stream with the given error, and
   * `message.queue.fail(e,true)` terminates the stream with the given error, ignoring queued messages. 
   * 
   * Note that the memory usage of the actor can grow unbounded if 
   * `enqueue` messages are sent to the actor faster than 
   * they are dequeued by whatever consumes the output `Process`.
   * Use the `message.queue.size` message to asynchronously check the
   * queue size and throttle whatever is feeding the actor messages.
   */
  def queue[A](implicit S: Strategy): (Actor[message.queue.Msg[A]], Process[Task, A]) = {
    import message.queue._
    var q = Queue[Throwable \/ A]()
    var n = 0 // size of q
    var done = false
    var listeners: Queue[(Throwable \/ A) => Unit] = Queue()
    val a: Actor[Msg[A]] = Actor.actor { msg => println(msg); msg match {
      case Enqueue(a) if !done =>
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
      case Close(cancel) if !done => 
        if (cancel) { q = Queue(-\/(Process.End)); n = 0 }
        else q = q.enqueue(-\/(Process.End))
        done = true
      case Fail(e,cancel) if !done => 
        if (cancel) { q = Queue(-\/(e)); n = 0 }
        else q = q.enqueue(-\/(e))
        done = true
      case QueueSize(cb) => cb(n)
      case _ => ()
    }}
    val p = Process.repeatWrap { Task.async[A] { cb => a ! Dequeue(cb) } }
    (a, p)
  }

  /** 
   * Returns a continuous `Process` stream whose value can be set 
   * asynchronously using the returned `Actor`.
   * 
   * `message.variable.set(a)` sets the value of the stream,
   * `message.variable.close` terminates the stream, 
   * `message.variable.fail(e)` terminates the stream with the given error, and
   * `message.variable.onRead(cb)` registers the given action to be run when 
   * the variable is first read. 
   * 
   * Note that the memory usage of the actor can grow unbounded if 
   * `Msg.Enqueue` messages are sent to the actor faster than 
   * they are dequeued by whatever consumes the output `Process`.
   * Use the `Msg.QueueSize` message to asynchronously check the
   * queue size and throttle whatever is feeding the actor messages.
   */
  def variable[A](implicit S: Strategy): (Actor[message.variable.Msg[A]], Process[Task, A]) = {
    import message.variable._
    var ref: Throwable \/ A = null 
    var done = false
    var listeners: Queue[(Throwable \/ A) => Unit] = null 
    var onRead = () => { () }
    val a: Actor[Msg[A]] = Actor.actor { a => 
      if (!done) a match {
        case Set(a) =>
          ref = \/-(a)
          if (!(listeners eq null)) { 
            listeners.foreach { _(ref) }
            listeners = null
          }
        case Get(cb) => 
          if (ref eq null) { 
            if (listeners eq null) listeners = Queue()
            listeners = listeners.enqueue(cb)
            onRead()
          }
          else
            cb(ref)
        case Close() => 
          ref = -\/(Process.End)
          done = true
        case Fail(e) => 
          ref = -\/(e)
          done = true
        case OnRead(cb) => 
          val h = onRead
          onRead = () => { h(); cb() }
      }
    }
    val p = Process.repeatWrap { Task.async[A] { cb => a ! Get(cb) } }
    (a, p)
  }

  /** Convert an `Actor[A]` to a `Sink[Task, A]`. */
  def toSink[A](snk: Actor[A]): Process[Task, A => Task[Unit]] = 
    Process.repeatWrap { Task.now { (a: A) => Task.delay { snk ! a } } }

}

object actor extends actor

object message {

  object queue {
    trait Msg[A]
    case class Dequeue[A](callback: (Throwable \/ A) => Unit) extends Msg[A] 
    case class Enqueue[A](a: A) extends Msg[A]
    case class Fail[A](error: Throwable, cancel: Boolean) extends Msg[A]
    case class Close[A](cancel: Boolean) extends Msg[A]
    case class QueueSize[A](callback: Int => Unit) extends Msg[A]

    def enqueue[A](a: A): Msg[A] =
      Enqueue(a)

    def dequeue[A](cb: A => Unit, onError: Throwable => Unit = t => ()): Msg[A] = 
      Dequeue {
        case -\/(e) => onError(e)
        case \/-(a) => cb(a)
      }

    def size[A](cb: Int => Unit): Msg[A] = QueueSize(cb)
    
    def close[A]: Msg[A] = Close[A](false)
    def cancel[A]: Msg[A] = Close[A](true)
    def fail[A](err: Throwable, cancel: Boolean = false): Msg[A] = Fail(err, cancel)
  }

  object variable {
    trait Msg[A]
    case class Set[A](a: A) extends Msg[A]
    case class Get[A](callback: (Throwable \/ A) => Unit) extends Msg[A]
    case class Close[A]() extends Msg[A]
    case class Fail[A](error: Throwable) extends Msg[A]
    case class OnRead[A](action: () => Unit) extends Msg[A]

    def set[A](a: A): Msg[A] = Set(a) 
    def get[A](callback: (Throwable \/ A) => Unit): Msg[A] = Get(callback) 
    def onRead[A](action: () => Unit): Msg[A] = OnRead(action) 
    def close[A]: Msg[A] = Close[A]()
    def fail[A](err: Throwable): Msg[A] = Fail(err)
  }
}
