package scalaz.stream.async.mutable

import java.util.concurrent.atomic.AtomicInteger

import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process.Halt
import scalaz.stream.async.immutable
import scalaz.stream.{Terminated, Kill, Error, End, Cause, Util, Process, Sink}
import scalaz.{Either3, -\/, \/, \/-}
import scalaz.\/._


/**
 * Queue that allows asynchronously create process or dump result of processes to
 * create eventually new processes that drain from this queue.
 *
 * Queue may have bound on its size, and that causes queue to control publishing processes
 * to stop publish when the queue reaches the size bound.
 * Bound (max size) is specified when queue is created, and may not be altered after.
 *
 * Queue also has signal that signals size of queue whenever that changes.
 * This may be used for example as additional flow control signalling outside this queue.
 *
 *
 */
trait Queue[A] {

  /**
   * Provides a Sink, that allows to enqueue `A` in this queue
   */
  def enqueue: Sink[Task, A]

  /**
   * Enqueue one element in this Queue. Resulting task will terminate
   * with failure if queue is closed or failed.
   * Please note this will get completed _after_ `a` has been successfully enqueued.
   * @param a `A` to enqueue
   * @return
   */
  def enqueueOne(a: A): Task[Unit]

  /**
   * Enqueue sequence of `A` in this queue. It has same semantics as `enqueueOne`
   * @param xa sequence of `A`
   * @return
   */
  def enqueueAll(xa: Seq[A]): Task[Unit]

  /**
   * Provides a process that dequeue from this queue.
   * When multiple consumers dequeue from this queue,
   * Then they dequeue from this queue in first-come, first-server order.
   *
   * Please use `Topic` instead of `Queue` when you have multiple subscribers that
   * want to see each `A` value enqueued.
   *
   */
  def dequeue: Process[Task, A]

  /**
   * Provides signal, that contains size of this queue.
   * Please note the discrete source from this signal only signal actual changes of state, not the
   * values being enqueued or dequeued.
   * That means elements can get enqueued and dequeued, but signal of size may not update,
   * because size of the queue did not change.
   *
   */
  def size: scalaz.stream.async.immutable.Signal[Int]

  /**
   * Closes this queue. This has effect of enqueue Sink to be stopped
   * and dequeue process to be stopped immediately after last `A` being drained from queue.
   *
   * After this any enqueue to this topic will fail with `Terminated(End)`, and enqueue `sink` will terminate with `End` cause.
   *
   * If, there are any `A` in queue while the queue is being closed, any new subscriber will receive that `A`
   * unless the queue is fully drained.
   *
   */
  def close: Task[Unit] = failWithCause(End)


  /**
   * Kills the queue. That causes all dequeuers from this queue immediately to stop with `Kill` cause.
   * any `A` in the queue at time of executing this task is lost.
   *
   * Any attempt to enqueue in this queue will result in failure with Terminated(Kill) exception.
   *
   * Task will be completed once all dequeuers and enqueuers are signalled.
   *
   */
  def kill: Task[Unit] = failWithCause(Kill)


  /**
   * Like `kill`, except it terminates with supplied reason.
   */
  def fail(rsn: Throwable): Task[Unit] = failWithCause(Error(rsn))



  private[stream] def failWithCause(c:Cause): Task[Unit]

}


private[stream] object Queue {


  /**
   * Builds a queue, potentially with `source` producing the streams that
   * will enqueue into queue. Up to `bound` size of `A` may enqueue into queue,
   * and then all enqueue processes will wait until dequeue.
   *
   * @param bound   Size of the bound. When <= 0 the queue is `unbounded`.
   * @tparam A
   * @return
   */
  def apply[A](bound: Int = 0)(implicit S: Strategy): Queue[A] = {

    sealed trait M
    case class Enqueue (a: Seq[A], cb: Throwable \/ Unit => Unit) extends M
    case class Dequeue (ref:ConsumerRef, cb: Throwable \/ A => Unit) extends M
    case class Fail(cause: Cause, cb: Throwable \/ Unit => Unit) extends M
    case class GetSize(cb: (Throwable \/ Seq[Int]) => Unit) extends M
    case class ConsumerDone(ref:ConsumerRef) extends M

    // reference to identify differed subscribers
    class ConsumerRef


    //actually queued `A` are stored here
    var queued = Vector.empty[A]

    // when this queue fails or is closed the reason is stored here
    var closed: Option[Cause] = None

    // consumers waiting for `A`
    var consumers: Vector[(ConsumerRef, Throwable \/ A => Unit)] = Vector.empty

    // publishers waiting to be acked to produce next `A`
    var unAcked: Vector[Throwable \/ Unit => Unit] = Vector.empty

    // if at least one GetSize was received will start to accumulate sizes change.
    // when defined on left, contains sizes that has to be published to sizes topic
    // when defined on right, awaiting next change in queue to signal size change
    // when undefined, signals no subscriber for sizes yet.
    var sizes:  Option[Vector[Int] \/ ((Throwable \/ Seq[Int]) => Unit)] = None

    // signals to any callback that this queue is closed with reason
    def signalClosed[B](cb: Throwable \/ B => Unit) =
      closed.foreach(rsn => S(cb(-\/(Terminated(rsn)))))

    // signals that size has been changed.
    // either keep the last size or fill the callback
    // only updates if sizes != None
    def signalSize(sz: Int): Unit = {
      sizes = sizes.map( cur =>
        left(cur.fold (
            szs => { szs :+ sz }
            , cb => { S(cb(\/-(Seq(sz)))) ; Vector.empty[Int] }
          ))
      )
    }




    // publishes single size change
    def publishSize(cb: (Throwable \/ Seq[Int]) => Unit): Unit = {
      sizes =
        sizes match {
          case Some(sz) => sz match {
            case -\/(v) if v.nonEmpty => S(cb(\/-(v))); Some(-\/(Vector.empty[Int]))
            case _                    => Some(\/-(cb))
          }
          case None => S(cb(\/-(Seq(queued.size)))); Some(-\/(Vector.empty[Int]))
        }
    }

    //dequeue one element from the queue
    def dequeueOne(ref: ConsumerRef, cb: (Throwable \/ A => Unit)): Unit = {
      queued.headOption match {
        case Some(a) =>
          S(cb(\/-(a)))
          queued = queued.tail
          signalSize(queued.size)
          if (unAcked.size > 0 && bound > 0 && queued.size < bound) {
            val ackCount = bound - queued.size min unAcked.size
            unAcked.take(ackCount).foreach(cb => S(cb(\/-(()))))
            unAcked = unAcked.drop(ackCount)
          }

        case None =>
          val entry : (ConsumerRef, Throwable \/ A => Unit)  = (ref -> cb)
          consumers = consumers :+ entry
      }
    }

    def enqueueOne(as: Seq[A], cb: Throwable \/ Unit => Unit) = {
      import scalaz.stream.Util._
      queued = queued fast_++ as

      if (consumers.size > 0 && queued.size > 0) {
        val deqCount = consumers.size min queued.size

        consumers.take(deqCount).zip(queued.take(deqCount))
        .foreach { case ((_,cb), a) => S(cb(\/-(a))) }

        consumers = consumers.drop(deqCount)
        queued = queued.drop(deqCount)
      }

      if (bound > 0 && queued.size >= bound) unAcked = unAcked :+ cb
      else S(cb(\/-(())))

      signalSize(queued.size)
    }

    def stop(cause: Cause, cb: Throwable \/ Unit => Unit): Unit = {
      closed = Some(cause)
      if (queued.nonEmpty && cause == End) {
        unAcked.foreach(cb => S(cb(-\/(Terminated(cause)))))
      } else {
        (consumers.map(_._2) ++ unAcked).foreach(cb => S(cb(-\/(Terminated(cause)))))
        consumers = Vector.empty
        sizes.flatMap(_.toOption).foreach(cb => S(cb(-\/(Terminated(cause)))))
        sizes = None
        queued = Vector.empty
      }
      unAcked = Vector.empty
      S(cb(\/-(())))
    }


    val actor: Actor[M] = Actor({ (m: M) =>
     Util.debug(s"### QUE  m: $m | cls: $closed | sizes $sizes, | queued $queued | consumers: $consumers")
      if (closed.isEmpty) m match {
        case Dequeue(ref, cb)     => dequeueOne(ref, cb)
        case Enqueue(as, cb) => enqueueOne(as, cb)
        case Fail(cause, cb)   => stop(cause, cb)
        case GetSize(cb)     => publishSize(cb)
        case ConsumerDone(ref) =>  consumers = consumers.filterNot(_._1 == ref)

      } else m match {
        case Dequeue(ref, cb) if queued.nonEmpty => dequeueOne(ref, cb)
        case Dequeue(ref, cb)                    => signalClosed(cb)
        case Enqueue(as, cb)                     => signalClosed(cb)
        case GetSize(cb) if queued.nonEmpty      => publishSize(cb)
        case GetSize(cb)                         => signalClosed(cb)
        case Fail(_, cb)                         => S(cb(\/-(())))
        case ConsumerDone(ref)                   =>  consumers = consumers.filterNot(_._1 == ref)
      }
    })(S)

    new Queue[A] {
      def enqueue: Sink[Task, A] = Process.constant(enqueueOne _)
      def enqueueOne(a: A): Task[Unit] = enqueueAll(Seq(a))
      def dequeue: Process[Task, A] = {
        Process.await(Task.delay(new ConsumerRef))({ ref =>
          Process.repeatEval(Task.async[A](cb => actor ! Dequeue(ref, cb)))
          .onComplete(Process.eval_(Task.delay(actor ! ConsumerDone(ref))))
        })
      }

      val size: immutable.Signal[Int] = {
        val sizeSource : Process[Task,Int] =
          Process.repeatEval(Task.async[Seq[Int]](cb => actor ! GetSize(cb)))
          .flatMap(Process.emitAll)
        Signal(sizeSource.map(Signal.Set.apply), haltOnSource =  true)(S)
      }

      def enqueueAll(xa: Seq[A]): Task[Unit] = Task.async(cb => actor ! Enqueue(xa,cb))

      private[stream] def failWithCause(c: Cause): Task[Unit] = Task.async[Unit](cb => actor ! Fail(c,cb))
    }

  }


}
