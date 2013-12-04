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
    var q: (Queue[A] \/ Queue[(Throwable \/ A) => Unit]) = left(Queue())

    // var q = Queue[Throwable \/ A]()
    var done:Option[Throwable] = None
    val a: Actor[Msg[A]] = Actor.actor {

      case Enqueue(a) if done.isEmpty => q match {
        case -\/(ready) =>
          q = left(ready :+ a)
        case \/-(listeners) =>
          if (listeners.isEmpty) q = left(Queue(a))
          else {
            val (cb, l2) = listeners.dequeue
            q = if (l2.isEmpty) left(Queue()) else right(l2)
            S(cb(right(a)))
          }
      }

      case Dequeue(cb) => q match {
        case -\/(ready) if ready.isEmpty && done.isDefined => cb(left(done.get))
        case -\/(ready) if ready.isEmpty => q = right(Queue(cb))
        case -\/(ready) =>
          val (a, r2) = ready.dequeue
          q = left(r2)
          S(cb(right(a)))

        case \/-(listeners) => q = right(listeners :+ cb)
      }

      case Close(rsn,cancel) if done.isEmpty => q match {
        case -\/(ready) =>
          if (cancel) q = left(Queue())
          done = Some(rsn)
        case \/-(listeners) =>
          val end = left(rsn)
          listeners.foreach(cb=>S(cb(end)))
          q = -\/(Queue())
          done = Some(rsn)
      }

      //Close when `done` Enqueue when `done`
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
