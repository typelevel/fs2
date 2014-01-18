package scalaz.stream.merge

import scala.collection.immutable.Queue
import scalaz.\/._
import scalaz.stream.Process._
import scalaz.stream.async.mutable.Signal
import scalaz.stream.merge.Junction._
import scalaz.stream.process1
import scalaz.{\/, -\/}

protected[stream] object JunctionStrategies {

  /** Typed constructor helper to create Junction.Strategy */
  def junction[W, I, O](f: JunctionSignal[W, I, O] => JunctionStrategy[W, I, O]): JunctionStrategy[W, I, O] =
    receive1[JunctionSignal[W, I, O], JunctionAction[W, O]](f)


  /**
   * Bounded Queue strategy, where every `A` received is distributed to all downstream on first-come, first-serve basis.
   * Queue may have max parameter defined. This allows to restrict size of queue, and stops to taking more `A` from
   * upstreams when size of internal queue is same or exceeds max size.
   * @param max when <= 0, indicates queue is not bounded, otherwise controls when upstreams will get allowed to push more `A`
   */
  def boundedQ[A](max: Int): JunctionStrategy[Int, A, A] = {
    val bounded = max > 0
    def drain(q: Queue[A], rsn: Throwable): JunctionStrategy[Int, A, A] =
      junction[Int, A, A] {
        case Open(jx, ref: UpRef)    => jx.close(ref, rsn) fby drain(q, rsn)
        case Open(jx, ref: DownRefW) => jx.writeW(q.size, ref)  fby drain(q, rsn)
        case Receive(jx, _, ref)      => jx.close(ref, rsn) fby drain(q, rsn)
        case Ready(jx, ref: DownRefO) =>
          val (a, nq) = q.dequeue
          val next = jx.writeO(a, ref) fby jx.broadcastW(nq.size)
          if (nq.size > 0) next fby drain(nq, rsn)
          else next fby Halt(rsn)
        case o                        =>
          drain(q, rsn)
      }

    def go(q: Queue[A]): JunctionStrategy[Int, A, A] =
      junction[Int, A, A] {
        case Open(jx, ref: UpRef) =>
          if (bounded && q.size >= max) go(q)
          else jx.more(ref) fby go(q)

        case Open(jx, ref: DownRefW) =>
          jx.writeW(q.size, ref) fby go(q)

        case Receive(jx, sa, ref)     =>
          val (nq, distribute) = jx.distributeO(q ++ sa, jx.downReadyO)
          val next = distribute fby jx.broadcastW(nq.size) fby go(nq)
          if (!bounded || nq.size < max) jx.more(ref) fby next
          else next

        case Ready(jx, ref: DownRefO) =>
          if (q.nonEmpty) {
            val (a, nq) = q.dequeue
            val next = jx.writeO(a, ref) fby jx.broadcastW(nq.size) fby go(nq)
            if (bounded && nq.size < max && jx.upReady.nonEmpty) jx.moreAll fby next
            else next
          } else {
            if (jx.upReady nonEmpty) jx.moreAll fby go(q)
            else go(q)
          }

        case DoneDown(jx, rsn)        =>
            if (q.nonEmpty && jx.downO.nonEmpty) jx.closeAllUp(rsn) fby drain(q, rsn)
            else Halt(rsn)

        case o =>
        go(q)
      }

    go(Queue())
  }


  /**
   * Converts Writer1 to JunctionStrategy.
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
  def liftWriter1[W, I, O](w: Writer1[W, I, O]):  JunctionStrategy[W, I, O] = {
    def go(cur: Writer1[W, I, O], last: Option[W]):  JunctionStrategy[W, I, O] = {
      def lastW(swo:Seq[W\/O]) : Option[W] =  swo.collect({ case -\/(w) => w }).lastOption
      junction[W, I, O] {
        case Open(jx, ref: UpRef)    => jx.more(ref) fby go(cur, last)
        case Open(jx, ref: DownRefW) => last match {
          case Some(w0) => jx.writeW(w0, ref) fby go(cur, last)
          case None => cur.unemit match {
            case (swo, next) =>
              def goNext(ow: Option[W]) = next match {
                case hlt@Halt(rsn) => hlt
                case next          => go(next, ow)
              }
              lastW(swo) match {
                case s@Some(w) => jx.writeW(w,ref) fby goNext(s)
                case None      => goNext(None)
              }
          }
        }
        case Receive(jx, is, ref)    =>
          process1.feed(is)(cur).unemit match {
            case (swo, hlt@Halt(rsn)) =>
              jx.more(ref) fby jx.broadcastAllBoth(swo) fby hlt
            case (swo, next)          =>
              jx.more(ref) fby jx.broadcastAllBoth(swo) fby go(next, lastW(swo) orElse last)
          }
        case DoneDown(jx, rsn)       =>
          val (swo, _) = cur.killBy(rsn).unemit
          jx.broadcastAllBoth(swo) fby Halt(rsn)

        case _ => go(cur, last)
      }
    }

    go(w, None)
  }


  /**
   * MergeN strategy for mergeN combinator. Please see [[scalaz.stream.merge.mergeN]] for more details.
   */
  def mergeN[A](max:Int):JunctionStrategy[Nothing,A,A] = {

    def openNextIfNeeded(current:Int) : JunctionStrategy[Nothing,A,A] =
      if (max <= 0 || max > current)  emit(OpenNext) else halt

    def go(q:Queue[A],closedUp:Option[Throwable]) : JunctionStrategy[Nothing,A,A] = {
      junction[Nothing,A,A] {
        case Open(jx,ref:UpRef) =>
          if (q.size < jx.up.size) openNextIfNeeded(jx.up.size) fby jx.more(ref) fby go(q,closedUp)
          else openNextIfNeeded(jx.up.size) fby go(q,closedUp)

        case Open(jx,ref:DownRefO) =>
          if (jx.downO.size == 1) go(q,closedUp)
          else jx.close(ref,new Exception("Only one downstream allowed for mergeN"))

        case Receive(jx, as, ref) =>
        if (jx.downReadyO.nonEmpty) {
            jx.writeAllO(as,jx.downO.head) fby jx.more(ref) fby go(q,closedUp)
          } else {
            val nq = q.enqueue(scala.collection.immutable.Iterable.concat(as))
            if (nq.size < jx.up.size) jx.more(ref) fby go(nq,closedUp)
            else go(nq,closedUp)
          }

        case Ready(jx,ref:DownRefO) =>
          if (q.nonEmpty) jx.writeAllO(q,ref) fby jx.moreAll fby go(Queue(),closedUp)
          else if (jx.up.isEmpty && closedUp.isDefined) Halt(closedUp.get)
          else jx.moreAll fby go(q,closedUp)

        case DoneUp(jx,rsn) =>
          if (jx.up.nonEmpty || q.nonEmpty) go(q,Some(rsn))
          else Halt(rsn)

        case Done(jx,_:UpRef,End) => closedUp match {
          case Some(rsn) if jx.up.isEmpty && q.isEmpty => Halt(rsn)
          case _ => openNextIfNeeded(jx.up.size) fby go(q,closedUp)
        }

        case Done(jx,_:UpRef,rsn) => Halt(rsn)

        case Done(jx,_:DownRefO, rsn) =>
          if (jx.downO.isEmpty) Halt(rsn)
          else go(q,closedUp)

        case _ => go(q, closedUp)

      }
    }

    emit(OpenNext) fby go(Queue(),None)
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
  def publishSubscribe[A]: JunctionStrategy[Nothing, A, A] = liftWriter1(writers.echoO[A])

  /**
   * Signal merge strategy, that interprets [[scalaz.stream.async.mutable.Signal]] algebra and produces discrete
   * source of signal
   * @tparam A
   * @return
   */
  def signal[A]: JunctionStrategy[A, Signal.Msg[A], Nothing] = liftWriter1(writers.signal[A])

}
