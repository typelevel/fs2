package scalaz.stream.actor

import scala._
import scalaz._
import scalaz.concurrent.{Strategy, Actor, Task}
import scalaz.stream.Process._
import scalaz.stream.Step
import scalaz.stream.wye.{AwaitBoth, AwaitR, AwaitL}
import scalaz.stream.{Process, wye}


object debug {
  def apply(s: String, o:Any*) {
   // println(s, o)
  }
}

/**
 * Created by pach on 11/18/13.
 */
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

    def ready(r: \/[Throwable, Step[Task, A]]): Unit = r match {
      case -\/(e)    => state = Some(Halt(e))
      case \/-(step) => step.head match {
        case \/-(head) => h = head; state = Some(step.tail)
        case -\/(e)    => state = Some(Halt(e))
      }
    }

    //runs single step
    //todo: maybe unemit first here ?
    def run(p: Process[Task, A]): Unit = {
      debug("<<RUN", this)
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
    def tryPull: Unit = state.foreach(pull)

    // eventually kills the process, if not killed yet or is not just running
    def kill(e: Throwable): Unit = {
      state match {
        case done@Some(Halt(_)) => //no-op
        case Some(next)         => state = None; run(next.killBy(e))
        case None               => //no-op
      }
    }

    def haltedBy: Option[Throwable] = state.collect { case Halt(e) => e }

    def halted = state.exists {
      case Halt(_) => true
      case _       => false
    }

    // feeds to wye, even when data are not ready
    def feed(y2: Wye[L, R, O]): Wye[L, R, O]

    //tries to feed wye, if there are data ready. If data are not ready, returns None
    def tryFeed(y2: Wye[L, R, O]): Option[Wye[L, R, O]] =
      if (h.nonEmpty) {val yn = Some(feed(y2)); h = Nil; yn } else None

    // feeds the wye with head
    def feedOrPull(y2: Process.Wye[L, R, O]): Option[Process.Wye[L, R, O]] = {
      state match {
        case Some(Halt(e)) => Some(y2.killBy(e))
        case Some(next)    => tryFeed(y2) match {
          case fed@Some(_) => h = Nil; fed
          case None        => state = None; pull(next); None
        }
        case None          => None
      }
    }

  }


  def wyeActor[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y: Wye[L, R, O]): Process[Task, O] = {

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
      def feed(y2: Process.Wye[L, R, O]): Wye[L, R, O] = wye.feedL(h)(y2)
      override def toString: String = "Left"
    }
    case class RightWyeSide(val actor: Actor[Msg]) extends WyeSideOps[R, L, R, O] {
      var state: Option[Process[Task, R]] = Some(pr)
      def switchBias: Unit = leftBias = true
      def bias: Boolean = !leftBias
      def feed(y2: Process.Wye[L, R, O]): Wye[L, R, O] = wye.feedR(h)(y2)
      override def toString: String = "Right"
    }

    def runWye(left: LeftWyeSide, right: RightWyeSide) = {
      def go(y2: Wye[L, R, O]): Wye[L, R, O] = {
        y2 match {
          case AwaitL(_, _, _) =>
            debug("==AWL", left.state, right.state, y2)
            left.feedOrPull(y2) match {
              case Some(next) => go(next)
              case None       => y2
            }

          case AwaitR(_, _, _) =>
            debug("==AWR", left.state, right.state, y2)
            right.feedOrPull(y2) match {
              case Some(next) => go(next)
              case None       => y2
            }


          case AwaitBoth(_, _, _) =>
            debug("==AWB", left.bias, left.state, right.state, y2)

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
            debug("##EMT", h, next, out)
            out match {
              case Some(cb) => out = None; cb(\/-(h)); next
              case None     => y2
            }

          case Halt(e) =>
            debug("##HLT", e)
            left.kill(e)
            right.kill(e)
            if (left.halted && right.halted) {
              out match {
                case Some(cb) => out = None; cb(-\/(e)); y2
                case None     => y2
              }
            } else {
              y2
            }
        }
      }
      yy = go(yy)
    }

    var L: LeftWyeSide = null
    var R: RightWyeSide = null


    val a = Actor.actor[Msg]({

      case Ready(side, step) =>
        side.ready(step)
        debug(">>RDY", side, L.state, R.state, out.isDefined)
        runWye(L, R)

      case get: Get[O@unchecked] =>
        debug(">>GET")
        out = Some(get.cb)
        runWye(L, R)

      case done: Done[O@unchecked] =>
        debug("!!DONE")
        out = Some(done.cb)
        yy = yy.killBy(done.rsn)
        runWye(L, R)

    })(Strategy.Sequential)

    L = LeftWyeSide(a)
    R = RightWyeSide(a)

    repeatEval(Task.async[Seq[O]](cb => a ! Get(cb)).map { v => debug("GETF", v); v }).flatMap(emitSeq(_)).map { v => debug("OUTP", v); v } onComplete
      suspend(eval(Task.async[Seq[O]](cb => a ! Done(End, cb))).drain)


  }


}
