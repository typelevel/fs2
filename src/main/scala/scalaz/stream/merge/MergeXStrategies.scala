package scalaz.stream.merge

import scala.collection.immutable.Queue
import scalaz.\/._
import scalaz.stream.Process._
import scalaz.stream.async.mutable.Signal
import scalaz.stream.merge.MergeX._
import scalaz.stream.process1
import scalaz.{\/, -\/}

object MergeXStrategies {

  /** Typed constructor helper to create mergeX strategy */
  def mergeX[W, I, O](f: MergeSignal[W, I, O] => MergeXStrategy[W, I, O]): MergeXStrategy[W, I, O] =
    receive1[MergeSignal[W, I, O], MergeAction[W, O]](f)


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
            if (q.nonEmpty && mx.downO.nonEmpty) mx.closeAllUp(rsn) fby drain(q, rsn)
            else Halt(rsn)

        case o =>
        go(q)
      }

    go(Queue())
  }


  /**
   * Converts Writer1 to MergeXStrategy.
   *
   * Like publish-subscribe merging strategy backed by supplied Writer1.
   * Any `I` received from upstream will be published to _all_ downstreams on `O` side if emmited by
   * Writer1 as `O` or, to downstreams on `W` side if emitted by Writer1 as `W`.
   *
   * Additionally all `W` downstreams will see last `W` emitted from Writer1. If there is no `W` yet
   * emitted by Writer1 downstreams on `W` side will wait until one will be available.
   *
   * Note this strategy terminates when Writer1 terminates or when downstream is closed.
   *
   * @return
   */
  def liftWriter1[W, I, O](w: Writer1[W, I, O]): MergeXStrategy[W, I, O] = {
    def go(cur: Writer1[W, I, O], last: Option[W]): MergeXStrategy[W, I, O] = {
      def lastW(swo:Seq[W\/O]) : Option[W] =  swo.collect({ case -\/(w) => w }).lastOption
      mergeX[W, I, O] {
        case Open(mx, ref: UpRef)    => mx.more(ref) fby go(cur, last)
        case Open(mx, ref: DownRefW) => last match {
          case Some(w0) => mx.writeW(w0, ref) fby go(cur, last)
          case None => cur.unemit match {
            case (swo, next) =>
              def goNext(ow: Option[W]) = next match {
                case hlt@Halt(rsn) => hlt
                case next          => go(next, ow)
              }
              lastW(swo) match {
                case s@Some(w) => mx.writeW(w,ref) fby goNext(s)
                case None      => goNext(None)
              }
          }
        }
        case Receive(mx, is, ref)    =>
          process1.feed(is)(cur).unemit match {
            case (swo, hlt@Halt(rsn)) =>
              mx.more(ref) fby mx.broadcastAllBoth(swo) fby hlt
            case (swo, next)          =>
              mx.more(ref) fby mx.broadcastAllBoth(swo) fby go(next, lastW(swo) orElse last)
          }
        case DoneDown(mx, rsn)       =>
          val (swo, _) = cur.killBy(rsn).unemit
          mx.broadcastAllBoth(swo) fby Halt(rsn)

        case _ => go(cur, last)
      }
    }

    go(w, None)
  }


  /**
   * MergeN strategy for mergeN combinator. Please see [[scalaz.stream.merge.mergeN]] for more details.
   */
  def mergeN[A]:MergeXStrategy[Nothing,A,A] = {

    def go(q:Queue[A],closedUp:Option[Throwable]) : MergeXStrategy[Nothing,A,A] = {
      mergeX[Nothing,A,A] {
        case Open(mx,ref:UpRef) =>
          if (q.size < mx.up.size) mx.more(ref) fby go(q,closedUp)
          else go(q,closedUp)

        case Open(mx,ref:DownRefO) =>
          if (mx.downO.size == 1) go(q,closedUp)
          else mx.close(ref,new Exception("Only one downstream allowed for mergeN"))

        case Receive(mx, as, ref) =>
        if (mx.downReadyO.nonEmpty) {
            mx.writeAllO(as,mx.downO.head) fby mx.more(ref) fby go(q,closedUp)
          } else {
            val nq = q.enqueue(scala.collection.immutable.Iterable.concat(as))
            if (nq.size < mx.up.size) mx.more(ref) fby go(nq,closedUp)
            else go(nq,closedUp)
          }

        case Ready(mx,ref:DownRefO) =>
          if (q.nonEmpty) mx.writeAllO(q,ref) fby mx.moreAll fby go(Queue(),closedUp)
          else if (mx.up.isEmpty && closedUp.isDefined) Halt(closedUp.get)
          else mx.moreAll fby go(q,closedUp)

        case DoneUp(mx,rsn) =>
          if (mx.up.nonEmpty || q.nonEmpty) go(q,Some(rsn))
          else Halt(rsn)

        case Done(mx,_:UpRef,End) => closedUp match {
          case Some(rsn) if mx.up.isEmpty && q.isEmpty => Halt(rsn)
          case _ => go(q,closedUp)
        }

        case Done(mx,_:UpRef,rsn) => Halt(rsn)

        case Done(mx,_:DownRefO, rsn) =>
          if (mx.downO.isEmpty) Halt(rsn)
          else go(q,closedUp)

        case _ => go(q, closedUp)

      }
    }

    go(Queue(),None)
  }

  /** various writers used in merge strategies **/
  object writers {

    /** writer that only echoes `A` on `O` side **/
    def echoO[A]: Writer1[Nothing, A, A] = process1.id[A].map(right)

    /** Writer1 that interprets the Signal messages to provide discrete source of `A` **/
    def signal[A]: Writer1[A, Signal.Msg[A], Nothing] = {
      def go(oa: Option[A]): Writer1[A, Signal.Msg[A], Nothing] = {
        receive1[Signal.Msg[A], A \/ Nothing] {
          case Signal.Set(a)                                               => emit(left(a)) fby go(Some(a))
          case Signal.CompareAndSet(f: (Option[A] => Option[A])@unchecked) => f(oa) match {
            case Some(a) => emit(left(a)) fby go(Some(a))
            case None    => go(oa)
          }
          case Signal.Fail(rsn)                                            => Halt(rsn)
        }
      }
      go(None)
    }

  }


  /**
   * Publish-subscribe merge strategy, where every `A` received from upstream is delivered to all downstream
   * @tparam A
   * @return
   */
  def publishSubscribe[A]: MergeXStrategy[Nothing, A, A] = liftWriter1(writers.echoO[A])

  /**
   * Signal merge strategy, that interprets [[scalaz.stream.async.mutable.Signal]] algebra and produces discrete
   * source of signal
   * @tparam A
   * @return
   */
  def signal[A]: MergeXStrategy[A, Signal.Msg[A], Nothing] = liftWriter1(writers.signal[A])

}
