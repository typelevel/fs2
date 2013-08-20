package scalaz.stream

import scalaz._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.\/._

import collection.immutable.Queue

trait actor {

  /**
   * Returns a discrete `Process` stream that can be added to or
   * halted asynchronously by sending the returned `Actor` messages.
   *
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
    var q: Queue[Throwable \/ A] \/ Queue[(Throwable \/ A) => Unit] = left(Queue())
    // var q = Queue[Throwable \/ A]()
    var done = false
    val a: Actor[Msg[A]] = Actor.actor {
      case Enqueue(a) if !done => q match {
        case -\/(ready) => 
          q = left(ready.enqueue(right(a)))
        case \/-(listeners) => 
          if (listeners.isEmpty) q = left(Queue(right(a))) 
          else {
            val (cb, l2) = listeners.dequeue
            q = if (l2.isEmpty) left(Queue()) else right(l2)
            cb(right(a)) 
          }
      }
      case Dequeue(cb) => q match {
        case -\/(ready) =>
          if (ready.isEmpty) q = right(Queue(cb))
          else {
            val (a, r2) = ready.dequeue
            cb(a)
            q = left(r2) 
          }
        case \/-(listeners) => q = right(listeners.enqueue(cb))
      }
      case Close(cancel) if !done => q match {
        case -\/(ready) => 
          if (cancel) q = left(Queue(left(Process.End)))
          else { q = left(ready.enqueue(left(Process.End))) }
          done = true
        case \/-(listeners) =>
          val end = left(Process.End)
          listeners.foreach(_(end)) 
          q = left(Queue(end)) 
      }
      case Fail(e,cancel) if !done => q match {
        case -\/(ready) => 
          if (cancel) q = left(Queue(left(e)))
          else q = left(ready.enqueue(left(e)))
          done = true
        case \/-(listeners) => 
          val end = left(e)
          listeners.foreach(_(end))
          q = left(Queue(end))
      } 
      case _ => ()
    }
    val p = Process.repeatWrap { Task.async[A] { cb => a ! Dequeue(cb) } }
    (a, p)
  }

  /**
   * Like `queue`, but runs the actor locally, on whatever thread sends it messages.
   */
  def localQueue[A]: (Actor[message.queue.Msg[A]], Process[Task,A]) = 
    queue(Strategy.Sequential)

  /**
   * Like `variable`, but runs the actor locally, on whatever thread sends it messages.
   */
  def localVariable[A]: (Actor[message.ref.Msg[A]], Process[Task,A]) = 
    ref(Strategy.Sequential)

  /** Convert an `Actor[A]` to a `Sink[Task, A]`. */
  def toSink[A](snk: Actor[A]): Process[Task, A => Task[Unit]] =
    Process.repeatWrap { Task.now { (a: A) => Task.delay { snk ! a } } }

  /**
   * Returns a continuous `Process` whose value can be set
   * asynchronously using the returned `Actor`.
   *
   * `message.variable.set(a)` sets the value of the stream,
   * `message.variable.close` terminates the stream,
   * `message.variable.fail(e)` terminates the stream with the given error, and
   * `message.variable.onRead(cb)` registers the given action to be run when
   * the variable is first read.
   */
  def ref[A](implicit S: Strategy): (Actor[message.ref.Msg[A]], Process[Task, A]) = {
    import message.ref._
    var ref: Throwable \/ A = null
    var done = false
    var listeners: Queue[(Throwable \/ A) => Unit] = null
    var onRead = () => { () }
    val a: Actor[Msg[A]] = Actor.actor {
      case Set(a) if !done =>
        ref = right(a)
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
      case Close() if !done =>
        ref = left(Process.End)
        done = true
        if (listeners eq null) {}
        else { listeners.foreach(_(ref)); listeners = null }
      case Fail(e) if !done =>
        ref = left(e)
        done = true
        if (listeners eq null) {}
        else { listeners.foreach(_(ref)); listeners = null }
      case OnRead(cb) if !done =>
        val h = onRead
        onRead = () => { h(); cb() }
      case _ => ()
    }
    val p = Process.repeatWrap { Task.async[A] { cb => a ! Get(cb) } }
    (a, p)
  }
}

object actor extends actor

object message {

  object queue {
    trait Msg[A]
    case class Dequeue[A](callback: (Throwable \/ A) => Unit) extends Msg[A]
    case class Enqueue[A](a: A) extends Msg[A]
    case class Fail[A](error: Throwable, cancel: Boolean) extends Msg[A]
    case class Close[A](cancel: Boolean) extends Msg[A]

    def enqueue[A](a: A): Msg[A] =
      Enqueue(a)

    def dequeue[A](cb: A => Unit, onError: Throwable => Unit = t => ()): Msg[A] =
      Dequeue {
        case -\/(e) => onError(e)
        case \/-(a) => cb(a)
      }

    def close[A]: Msg[A] = Close[A](false)
    def cancel[A]: Msg[A] = Close[A](true)
    def fail[A](err: Throwable, cancel: Boolean = false): Msg[A] = Fail(err, cancel)
  }

  object ref {
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
