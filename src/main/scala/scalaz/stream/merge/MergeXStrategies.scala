package scalaz.stream.merge

import scala.collection.immutable.Queue
import scalaz.stream.Process._
import scalaz.stream.merge.MergeX._

object MergeXStrategies {

  /** Typed constructor helper to create mergeX strategy */
  def mergeX[W, I, O](f: MergeSignal[W, I, O] => MergeXStrategy[W, I, O]): MergeXStrategy[W, I, O] =
    receive1[MergeSignal[W, I, O], MergeAction[W, O]](f)

  /**
   * Publish-subscribe merge strategy, where every `A` received from upstream is delivered to all downstream
   * @tparam A
   * @return
   */
  def publishSubscribe[A]: MergeXStrategy[Nothing, A, A] =
    mergeX[Nothing, A, A] {
      case Open(mx, ref: UpRef) => mx.more(ref) fby publishSubscribe
      case Receive(mx, is, ref) => mx.more(ref) fby mx.broadcastAllO(is) fby publishSubscribe
      case DoneDown(mx, rsn)    => Halt(rsn)
      case _                    => publishSubscribe
    }


  /**
   * Bounded Queue strategy, where every `A` received is distributed to all downstream on first-come, first-serve basis.
   * Queue may have max parameter defined. This allows to restrict size of queue, and stops to taking more `A` from
   * upstreams when size of internal queue is same or exceeds max size.
   * @param max when <= 0, indicates queue is not bounded, otherwise controls when upstreams will get allowed to push more `A`
   */
  def boundedQ[A](max: Int): MergeXStrategy[Int, A, A] = {
    val bounded = max > 0
    def drain(q: Queue[A], rsn: Throwable): MergeXStrategy[Int, A, A] =
      mergeX[Int, A, A] {
        case Open(mx, ref: UpRef)    => mx.close(ref, rsn) fby drain(q, rsn)
        case Open(mx, ref: DownRefW) => mx.writeW(q.size, ref)  fby drain(q, rsn)
        case Receive(mx, _, ref)      => mx.close(ref, rsn) fby drain(q, rsn)
        case Ready(mx, ref: DownRefO) =>
          val (a, nq) = q.dequeue
          val next = mx.writeO(a, ref) fby mx.broadcastW(nq.size)
          if (nq.size > 0) next fby drain(nq, rsn)
          else next fby Halt(rsn)
        case o                        =>
          debug("BQDRAIN", o)
          drain(q, rsn)
      }

    def go(q: Queue[A]): MergeXStrategy[Int, A, A] =
      mergeX[Int, A, A] {
        case Open(mx, ref: UpRef) =>
          if (bounded && q.size >= max) go(q)
          else mx.more(ref) fby go(q)

        case Open(mx, ref: DownRefW) =>
          mx.writeW(q.size, ref) fby go(q)

        case Receive(mx, sa, ref)     =>
          val (nq, distribute) = mx.distributeO(q ++ sa, mx.downReadyO)
          val next = distribute fby mx.broadcastW(nq.size) fby go(nq)
          if (!bounded || nq.size < max) mx.more(ref) fby next
          else next

        case Ready(mx, ref: DownRefO) =>
          debug("BQRDY", ref, q, mx)
          if (q.nonEmpty) {
            val (a, nq) = q.dequeue
            val next = mx.writeO(a, ref) fby mx.broadcastW(nq.size) fby go(nq)
            if (bounded && nq.size < max && mx.upReady.nonEmpty) mx.moreAll fby next
            else next
          } else {
            if (mx.upReady nonEmpty) mx.moreAll fby go(q)
            else go(q)
          }

        case DoneDown(mx, rsn)        =>
          val p =
            if (q.nonEmpty) mx.closeAllUp(rsn) fby drain(q, rsn)
            else Halt(rsn)
          debug("BQDWNDONE", rsn, p, mx)
          p
        case o                        =>
          debug("BQGO", o)
          go(q)
      }

    go(Queue())
  }


}
