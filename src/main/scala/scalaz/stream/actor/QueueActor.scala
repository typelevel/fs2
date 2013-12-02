package scalaz.stream.actor

import scalaz.concurrent.{Task, Actor, Strategy}
import scalaz.stream.Process
import scala.collection.immutable.Queue
import scalaz.{\/-, -\/, \/}
import scalaz.\/._ 
import scalaz.stream.Process.End

trait QueueActor {

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
            S(cb(right(a)))
          }
      }
      case Dequeue(cb) if !done => q match {
        case -\/(ready) =>
          if (ready.isEmpty) q = right(Queue(cb))
          else {
            val (a, r2) = ready.dequeue
            S(cb(a))
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
          listeners.foreach(cb=>S(cb(end)))
          q = left(Queue(end))
      }
      case Fail(e,cancel) if !done => q match {
        case -\/(ready) =>
          if (cancel) q = left(Queue(left(e)))
          else q = left(ready.enqueue(left(e)))
          done = true
        case \/-(listeners) =>
          val end = left(e)
          listeners.foreach(cb=>S(cb(end)))
          q = left(Queue(end))
      }
      //below in case queue is done
      case Dequeue(cb) => cb(-\/(End))
      case oth => ()
    }
    val p = Process.repeatEval { Task.async[A] { cb => a ! Dequeue(cb) } }
    (a, p)
  }


  /**
   * Like `queue`, but runs the actor locally, on whatever thread sends it messages.
   */
  def localQueue[A]: (Actor[message.queue.Msg[A]], Process[Task,A]) =
    queue(Strategy.Sequential)



  /** Convert an `Actor[A]` to a `Sink[Task, A]`. */
  def toSink[A](snk: Actor[A]): Process[Task, A => Task[Unit]] =
    Process.repeatEval { Task.now { (a: A) => Task.delay { snk ! a } } }


}
