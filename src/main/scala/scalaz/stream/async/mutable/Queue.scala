package scalaz.stream.async.mutable

import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process.End
import scalaz.stream.async.immutable
import scalaz.stream.{Process, Sink}
import scalaz.{-\/, \/, \/-}


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
 * Please see [[scalaz.stream.merge.JunctionStrategies.boundedQ]] for more details.
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
   * Please note this is completed _AFTER_ this queue is completely drained and all publishers
   * to queue are terminated.
   *
   */
  def close: Task[Unit] = fail(End)

  /**
   * Closes this queue. Unlike `close` this is run immediately when called and will not wait until
   * all elements are drained.
   */
  def closeNow: Unit = close.runAsync(_=>())


  /**
   * Like `close`, except it terminates with supplied reason.
   */
  def fail(rsn: Throwable): Task[Unit]

  /**
   * like `closeNow`, only allows to pass reason fro termination
   */
  def failNow(rsn:Throwable) : Unit = fail(rsn).runAsync(_=>())

}


private[stream] object Queue {

  sealed trait M[+A]


  case class Enqueue[A](a: Seq[A], cb: Throwable \/ Unit => Unit) extends M[A]
  case class Dequeue[A](cb: Throwable \/ A => Unit) extends M[A]
  case class Fail(rsn: Throwable, cb: Throwable \/ Unit => Unit) extends M[Nothing]
  case class GetSize(cb: (Throwable \/ Seq[Int]) => Unit) extends M[Nothing]

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

    //actually queued `A` are stored here
    var queued = Vector.empty[A]

    // when this queue fails or is closed the reason is stored here
    var closed: Option[Throwable] = None

    // consumers waiting for `A`
    var consumers: Vector[Throwable \/ A => Unit] = Vector.empty

    // publishers waiting to be acked to produce next `A`
    var unAcked: Vector[Throwable \/ Unit => Unit] = Vector.empty

    // if at least one GetSize was received will start to accumulate sizes change.
    var sizes: Option[Vector[Int] \/ ((Throwable \/ Seq[Int]) => Unit)] = None

    // signals to any callback that this queue is closed with reason
    def signalClosed[B](cb: Throwable \/ B => Unit) = closed.foreach(rsn => S(cb(-\/(rsn))))

    // signals that size has been changed.
    def signalSize(sz: Int): Unit = sizes.foreach { curr =>
      val next = curr.fold(v => v :+ sz, cb => {cb(\/-(Seq(sz))); Vector.empty[Int] })
      sizes = Some(-\/(next))
    }

    val actor: Actor[M[A]] = Actor({ (m: M[A]) =>
      if (closed.isEmpty) m match {

        case Dequeue(cb: (Throwable \/ A => Unit)@unchecked) =>
          queued.lastOption match {
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
              consumers = consumers :+ cb
          }

        case Enqueue(as, cb) =>
          queued = queued ++ as

          if (consumers.size > 0 && queued.size > 0) {
            val deqCount = consumers.size min queued.size

            consumers.take(deqCount).zip(queued.take(deqCount))
            .foreach { case (cb, a) => S(cb(\/-(a))) }

            consumers = consumers.drop(deqCount)
            queued = queued.drop(deqCount)
          }

          if (bound > 0 && queued.size >= bound) unAcked = unAcked :+ cb
          else S(cb(\/-(())))

          signalSize(queued.size)


        case Fail(rsn, cb) =>
          closed = Some(rsn)
          (consumers ++ unAcked).foreach(cb => S(cb(-\/(rsn))))
          consumers = Vector.empty
          unAcked = Vector.empty
          queued = Vector.empty

          sizes.collect({ case \/-(cb) => cb }).foreach(cb => S(cb(-\/(rsn))))
          sizes = None

          S(cb(\/-(())))

        case GetSize(cb) => sizes match {
          case Some(\/-(_))     => //impossible
          case Some(-\/(sizes)) => cb(\/-(sizes))
          case None             => sizes = Some(\/-(cb))
        }


      } else m match {

        case Dequeue(cb)     => signalClosed(cb)
        case Enqueue(as, cb) => signalClosed(cb)
        case GetSize(cb)     => signalClosed(cb)
        case Fail(_, cb)   => S(cb(\/-(())))

      }


    })(S)



    new Queue[A] {
      def enqueue: Sink[Task, A] = Process.constant(enqueueOne _)
      def enqueueOne(a: A): Task[Unit] = enqueueAll(Seq(a))
      def dequeue: Process[Task, A] = Process.repeatEval(Task.async[A](cb => actor ! Dequeue(cb)))
      def size: immutable.Signal[Int] = ??? // todo -> hook to signal 
      def enqueueAll(xa: Seq[A]): Task[Unit] = Task.async(cb => actor ! Enqueue(xa,cb))
      def fail(rsn: Throwable): Task[Unit] = Task.async(cb => actor ! Fail(rsn,cb))
    }

  }


}
