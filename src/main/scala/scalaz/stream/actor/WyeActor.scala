package scalaz.stream.actor

import scala._
import scala.annotation.tailrec
import scalaz._
import scalaz.concurrent.{Strategy, Actor, Task}
import scalaz.stream.Process._
import scalaz.stream.Step
import scalaz.stream.wye.{AwaitBoth, AwaitR, AwaitL}
import scalaz.stream.{Process, wye}

object WyeActor {

  trait WyeSide[A, L, R, O] {
    /** returns next wye after processing the result of the step **/
    def receive(r: \/[Throwable, Step[Task, A]])(y2: Wye[L, R, O]): Wye[L, R, O]
  }

  sealed trait Msg
  case class Ready[A, L, R, O](from: WyeSide[A, L, R, O], s: Throwable \/ Step[Task, A]) extends Msg
  case class Get[A](cb: (Throwable \/ Seq[A]) => Unit) extends Msg
  case class Done(rsn: Throwable, cb: (Throwable \/ Unit) => Unit) extends Msg

  /**
   * Actor that backs the `wye`. Actor is reading non-deterministically from both sides
   * and interprets wye to produce output stream.
   *
   * @param pl left process
   * @param pr right process
   * @param y  wye to control queueing and merging
   * @param S  strategy, preferably executor service
   * @tparam L Type of left process element
   * @tparam R Type of right process elements
   * @tparam O Output type of resulting process
   * @return Process with merged elements.
   */
  def wyeActor[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y: Wye[L, R, O])(S: Strategy): Process[Task, O] = {

    trait WyeSideOps[A, L, R, O] extends WyeSide[A, L, R, O] {

      // Next step of process that feds into wye. If the is Empty, step is just running
      var p: Option[Process[Task, A]]

      // when this is set to`true`,
      // process is cleaning up so it shall not be again killed
      private var cleanup: Boolean = false

      def feedA(as: Seq[A])(y2: Wye[L, R, O]): Wye[L, R, O]
      def haltA(e: Throwable)(y2: Wye[L, R, O]): Wye[L, R, O]

      //feeds the wye by element or signals halt to wye, producing next state of process
      def receive(r: Throwable \/ Step[Task, A])(y2: Wye[L, R, O]): Wye[L, R, O] = {
        r match {
          case \/-(step) => step.head match {
            case \/-(h) =>
              p = Some(step.tail)
              step.tail match {
                case Halt(e) =>  haltA(e)(feedA(h)(y2))
                case _ => feedA(h)(y2)
              }
            case -\/(e) =>
              p = Some(Halt(e))
              haltA(e)(y2)
          }
          case -\/(e)    =>
            p = Some(Halt(e))
            haltA(e)(y2)
        }
      }

      def isHalt: Boolean = haltedBy.isDefined
      def haltedBy: Option[Throwable] = p.collect { case Halt(e) => e }

      //returns true when the process is cleaned, or runs the cleanup and returns false
      //if process is running is no-op and returns false
      def runCleanup(a: Actor[Msg], e: Throwable): Boolean = {
        p match {
          case Some(Halt(_)) => true
          case None     => false
          case Some(ps) =>
            ps.killBy(e).run.runAsync { cb => a ! Ready(this, cb.map(_ => Step.failed(e)))}
            false
        }
      }

      def pull(a: Actor[Msg]): Unit = {
        p match {
          case Some(Halt(_)) => //halted
          case Some(t) => p = None; run(t, a)
          case _       => //in request
        }
      }

      def run(s: Process[Task, A], actor: Actor[Msg]): Unit =
        s.runStep.runAsync { cb => actor ! Ready(this, cb) }

    }

    //current state of the wye
    var yy: Wye[L, R, O] = y

    //cb to be completed for `out` side
    var out: Option[(Throwable \/ Seq[O]) => Unit] = None

    //forward referenced actor
    var a: Actor[Msg] = null

    //Bias for reading from either left or right.
    var leftBias: Boolean = true

    case class LeftWyeSide(var p: Option[Process[Task, L]]) extends WyeSideOps[L, L, R, O] {
      def feedA(as: Seq[L])(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.feedL(as)(y2)
      def haltA(e: Throwable)(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.haltL(e)(y2)
      override def toString: String = "Left"
    }

    case class RightWyeSide(var p: Option[Process[Task, R]]) extends WyeSideOps[R, L, R, O] {
      def feedA(as: Seq[R])(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.feedR(as)(y2)
      def haltA(e: Throwable)(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.haltR(e)(y2)
      override def toString: String = "Right"
    }

    val L: LeftWyeSide = LeftWyeSide(Some(pl))
    val R: RightWyeSide = RightWyeSide(Some(pr))

    //switches right and left to cleanup (if not yet switched) and runs the cleanup
    def tryCleanup(e: Throwable): Boolean =
      L.runCleanup(a, e) && R.runCleanup(a, e)

    def completeOut(cb: (Throwable \/ Seq[O]) => Unit, r: Throwable \/ Seq[O]): Unit = {
      out = None
      S(cb(r))
    }

    @tailrec
    def tryCompleteOut(cb: (Throwable \/ Seq[O]) => Unit, y2: Wye[L, R, O]): Wye[L, R, O] = {
      y2.unemit match {
        case (h, ny) if h.nonEmpty =>
          completeOut(cb, \/-(h))
          ny

        case (_, ny@Halt(e)) =>
          if (tryCleanup(e)) completeOut(cb, -\/(e))
          ny

        case (_, ny@AwaitL(_, _, _)) =>
          L.haltedBy match {
            case Some(e) => tryCompleteOut(cb, ny.killBy(e))
            case None    => L.pull(a); ny
          }

        case (_, ny@AwaitR(_, _, _)) =>
          R.haltedBy match {
            case Some(e) => tryCompleteOut(cb, ny.killBy(e))
            case None    => R.pull(a); ny
          }

        case (_, ny@AwaitBoth(_, _, _)) =>
          if (L.isHalt && R.isHalt) {
            tryCompleteOut(cb, ny.killBy(L.haltedBy.get))
          } else {
            if (leftBias) { L.pull(a); R.pull(a) }
            else { R.pull(a); L.pull(a) }
            ny
          }

        case (_, Emit(_, _)) =>
          val e = new Exception("Impossible: Emit after unemit?")
          completeOut(cb,-\/(e))
          Halt(e)
      }
    }

    a = Actor.actor[Msg]({
      case Ready(side: WyeSide[Any, L, R, O]@unchecked, stepr) =>
        val ny = side.receive(stepr)(yy)
        leftBias = side == R
        yy = out match {
          case Some(cb) => ny.unemit match {
            case (h, y2@Halt(e)) if h.isEmpty =>
              if (tryCleanup(e)) completeOut(cb, -\/(e))
              y2

            case (h, y2) =>
              completeOut(cb, \/-(h))
              y2
          }
          case None     => ny
        }

      case get: Get[O@unchecked] =>
        out = Some(get.cb)
        yy = tryCompleteOut(get.cb, yy)

      case Done(rsn, cb) =>
        val cbOut = cb compose ((_: Throwable \/ Seq[O]) => \/-(()))
        out = Some(cbOut)
        yy = tryCompleteOut(cbOut, yy.killBy(rsn))

    })(S)

    repeatEval(Task.async[Seq[O]](cb => a ! Get(cb))).flatMap(emitSeq(_)) onComplete
      suspend(eval(Task.async[Unit](cb => a ! Done(End, cb)))).drain
  }

}
