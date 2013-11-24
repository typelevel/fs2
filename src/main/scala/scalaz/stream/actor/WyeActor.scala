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

  trait WyeSide[A] {
    def ready(r: Throwable \/ Step[Task, A])
  }

  sealed trait Msg
  case class Ready[A](from: WyeSide[A], s: Throwable \/ Step[Task, A]) extends Msg
  case class Get[A](cb: (Throwable \/ Seq[A]) => Unit) extends Msg
  case class Done[A](rsn: Throwable, cb: (Throwable \/ Seq[A]) => Unit) extends Msg


  // Operations to run for every side.
  // This is generalized version to be used for left and right side
  trait WyeSideOps[A, L, R, O] extends WyeSide[A] {

    val actor: Actor[Msg]

    //when this is set, process is not running and is waiting for next step run
    var state: Option[Process[Task, A]]

    //head that can be consumed before
    var h: Seq[A] = Nil

    //called when the step of process completed  to collect results set next state
    def ready(r: \/[Throwable, Step[Task, A]]): Unit = r match {
      case -\/(e)    =>  state = Some(Halt(e))
      case \/-(step) =>
        step.head match {
        case \/-(head) =>  h = head; state = Some(step.tail)
        case -\/(e)    =>   state = Some(Halt(e))
      }
    }

    //runs single step
    //todo: maybe unemit first here ?
    def run(p: Process[Task, A]): Unit = {
      state = None
      p.step.runLast.runAsync(cb => actor ! Ready(this, cb.flatMap(r => r.map(\/-(_)).getOrElse(-\/(End)))))
    }

    //switches bias to other side
    def switchBias: Unit

    //returns true if bias is on this side
    def bias: Boolean

    // pulls from process asynchronously to actor
    def pull(p: Process[Task, A]): Unit = {
      state = None
      switchBias
      run(p)
    }

    //tries to pull from process. If process is already pulling, it is no-op
    def tryPull: Unit = if (! halted) state.foreach(pull)

    // eventually kills the process, if not killed yet or is not just running
    def kill(e: Throwable): Unit = {
      if (! halted) {
        h = Nil
        state.foreach(p=>run(p.killBy(e)))
      }
    }

    //if stream is halted, returns the reason for halt
    def haltedBy: Option[Throwable] = state.collect { case Halt(e) => e }

    //returns true if stream is halt, and there are no more data to be consumed
    def halted = state.exists {
      case Halt(_) => true && h.isEmpty
      case _       => false
    }

    // feeds to wye. Shall be called only when data are ready to be fed
    protected def feed0(y2: Wye[L, R, O]): Wye[L, R, O]

    def feed(y2: Wye[L, R, O]): Wye[L, R, O] = {val ny = feed0(y2); h = Nil; ny}

    //tries to feed wye, if there are data ready. If data are not ready, returns None
    def tryFeed(y2: Wye[L, R, O]): Option[Wye[L, R, O]] =
      if (h.nonEmpty) Some(feed(y2)) else None

    // feeds the wye with head
    def feedOrPull(y2: Process.Wye[L, R, O]): Option[Process.Wye[L, R, O]] = {
      if (h.nonEmpty) {
         Some(feed(y2))
      } else {
        state match {
          case Some(Halt(e)) => Some(y2.killBy(e))
          case Some(next)    => tryFeed(y2) match {
            case fed@Some(_) => h = Nil; fed
            case None        => pull(next); None
          }
          case None          => None
        }
      }
    }

  }

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
  def wyeActor[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y: Wye[L, R, O])(implicit S: Strategy): Process[Task, O] = {

    //current state of the wye
    var yy: Wye[L, R, O] = y

    //if the `out` side is in the Get, this is set to callback that needs to be filled in
    var out: Option[(Throwable \/ Seq[O]) => Unit] = None

    //Bias for reading from either left or right.
    var leftBias: Boolean = true

    case class LeftWyeSide(val actor: Actor[Msg]) extends WyeSideOps[L, L, R, O] {
      var state: Option[Process[Task, L]] = Some(pl)
      def switchBias: Unit = leftBias = false
      def bias: Boolean = leftBias
      def feed0(y2: Process.Wye[L, R, O]): Wye[L, R, O] = wye.feedL(h)(y2)
      override def toString: String = "Left"
    }
    case class RightWyeSide(val actor: Actor[Msg]) extends WyeSideOps[R, L, R, O] {
      var state: Option[Process[Task, R]] = Some(pr)
      def switchBias: Unit = leftBias = true
      def bias: Boolean = !leftBias
      def feed0(y2: Process.Wye[L, R, O]): Wye[L, R, O] = wye.feedR(h)(y2)
      override def toString: String = "Right"
    }

    def runWye(left: LeftWyeSide, right: RightWyeSide) = {
      @tailrec
      def go(y2: Wye[L, R, O]): Wye[L, R, O] = {
        y2 match {
          case AwaitL(_, _, _) =>
            left.feedOrPull(y2) match {
              case Some(next) => go(next)
              case None       => y2
            }

          case AwaitR(_, _, _) =>
            right.feedOrPull(y2) match {
              case Some(next) => go(next)
              case None       => y2
            }

          case AwaitBoth(_, _, _) =>
            (if (left.bias) {
              left.tryFeed(y2) orElse right.tryFeed(y2)
            } else {
              right.tryFeed(y2) orElse left.tryFeed(y2)
            }) match {
              case Some(next)                          => go(next)
              case None if left.halted && right.halted => go(y2.killBy(left.haltedBy.get))
              case None                                => left.tryPull; right.tryPull; y2
            }

          case Emit(h, next) =>
            out match {
              case Some(cb) => out = None; S(cb(\/-(h))); next
              case None     => y2
            }

          case Halt(e) =>
            left.kill(e)
            right.kill(e)
            if (left.halted && right.halted) {
              out match {
                case Some(cb) => out = None; S(cb(-\/(e))); y2
                case None     => y2
              }
            } else {
              y2
            }
        }
      }
      yy = go(yy)
    }

    //unfortunately L/R must be stored as var due forward-referencing actor below
    var L: LeftWyeSide = null; var R: RightWyeSide = null

    val a = Actor.actor[Msg]({
      case Ready(side, step) =>
        side.ready(step)
        runWye(L, R)
      case get: Get[O@unchecked] =>
        out = Some(get.cb)
        runWye(L, R)
      case done: Done[O@unchecked] =>
        out = Some(done.cb)
        yy = yy.killBy(done.rsn)
        runWye(L, R)
    })(S)

    L = LeftWyeSide(a); R = RightWyeSide(a)

    repeatEval(Task.async[Seq[O]](cb => a ! Get(cb))).flatMap(emitSeq(_)) onComplete
      suspend(eval(Task.async[Seq[O]](cb => a ! Done(End, cb))).drain)
  }


}
