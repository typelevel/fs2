package scalaz.stream.actor

import scalaz.concurrent.{Actor, Task}
import scalaz.stream.Process
import scalaz.stream.Process._
import scalaz.stream.actor.message.wye.{Get, Ready, Side, Msg}
import scalaz.stream.wye
import scalaz.stream.wye.{AwaitBoth, AwaitR, AwaitL}
import scalaz.stream.{Step, Wye}
import scalaz.{\/-, -\/, \/}


/**
 * Created by pach on 11/18/13.
 */
trait WyeActor {


  def wyeActor[L, R, O](p1: Process[Task, L], p2: Process[Task, R])(y: Wye[L, R, O]): Process[Task, O] = {


    //when any of this is sed, this indicates that side is ready to be `read`
    //when unset, indicates the process is not ready to be ready and is likelly in await...
    var sl: Option[Throwable \/ Step[Task, L]] = None
    var sr: Option[Throwable \/ Step[Task, R]] = None

    //state of wye
    var yy = y

    //output to be taken
    var out: (Throwable \/ Seq[O]) \/ ((Throwable \/ Seq[O]) => Unit) = -\/(\/-(Nil))

    //bias to have fair queueing in case of AwaitBoth
    var bias: Side.Value = Side.L

    //we use step now, maybe shall optimize this a bit to avoid messaging in case of p being in Halt or Emit
    def pull[A](side: Side.Value)(p: Process[Task, A], actor: Actor[Msg]): Unit = {
      p.step.runLast.runAsync {
        cb =>
          val next = cb.fold(
          t => -\/(t)
          , {
            case Some(step) => \/-(step)
            case None       => -\/(End)
          })
          actor ! Ready(side, next)
      }
    }

    //terminates the p1 or p2
    //this is called from wye in halt, and actually
    def kill[A](side: Side.Value, rsn: Throwable, actor: Actor[Msg]) = {
      val s = side match {
        case Side.L => sl
        case Side.R => sr
      }

      s match {
        case Some(\/-(step: Step[Task, A])) =>
          side match {
            case Side.L => sl = None
            case Side.R => sr = None
          }
          (step.tail onComplete step.cleanup)
            .killBy(rsn).run.runAsync { _ => actor ! Ready(side, -\/(End)) }
        case _                              => //no-op hence we will run it once set (None=>Some) or is already failed
      }


    }


    //this interprets wye and is `protected` by actor
    // it keeps state of wye in `yy`
    def runWye(actor: Actor[Msg]) {
      def go(y2: Wye[L, R, O]): Wye[L, R, O] = {

        def readAndPull[A](side: Side.Value)
                          (as: => Option[Throwable \/ Step[Task, A]])
                          (feed: Seq[A] => Wye[L, R, O] => Wye[L, R, O])
                          (awy: Await[Env[L, R]#Y, A, O]): Option[Wye[L, R, O]] = {

          as match {
            case Some(-\/(End))  => println(s"$side is End");Some(awy.fallback1 onComplete awy.cleanup1)
            case Some(-\/(t))    => println(s"$side is t", t.getClass.getName);Some(awy.cleanup1)
            case Some(\/-(step)) =>
              pull(side)(step.tail, actor)
              side match {
                case Side.L => bias = Side.R
                case Side.R => bias = Side.L
              }
              Some(step.fold(hd => feed(hd)(awy))(awy.fallback1 onComplete awy.cleanup1, awy.cleanup1))
            case None            => None
          }
        }

        def readAndPullLeft(awy: Wye[L, R, O]) = readAndPull[L](Side.L)(sl.map { v => sl = None; v })(wye.feedL)(awy.asInstanceOf[Await[Env[L, R]#Y, L, O]])
        def readAndPullRight(awy: Wye[L, R, O]) = readAndPull[R](Side.R)(sr.map { v => sr = None; v })(wye.feedR)(awy.asInstanceOf[Await[Env[L, R]#Y, R, O]])


        y2 match {

          case h@Halt(rsn) =>
            //println("### HALT", rsn, sl,sr,out)
            if (sl.exists(_.isLeft) && sr.exists(_.isLeft)) {
              //both sides got killed we can `kill` the out side
              out match {
                case \/-(cb) => cb(-\/(rsn))
                case _       => out = -\/(-\/(rsn))
              }
            } else {
              kill(Side.L, rsn, actor)
              kill(Side.R, rsn, actor)
            }
            y2

          case Emit(h, nextY) =>
            if (h.nonEmpty) {
              out match {
                case \/-(cb)        => out = -\/(\/-(Nil)); cb(\/-(h)); go(nextY)
                case -\/(\/-(curr)) => out = -\/(\/-(curr ++ h)); go(nextY)
                case -\/(-\/(err))  => go(y2.killBy(err))
              }
            } else {
              go(nextY)
            }



          case AwaitL(rcv, fb, c) =>
            readAndPullLeft(y2) match {
              case Some(next) => go(next)
              case None       => y2
            }


          case AwaitR(rcv, fb, c) =>
            readAndPullRight(y2) match {
              case Some(next) => go(next)
              case None       => y2
            }

          case AwaitBoth(rcv, fb, c) =>

            val nextY =
              bias match {
                case Side.L => readAndPullLeft(y2) orElse readAndPullRight(y2)
                case Side.R => readAndPullRight(y2) orElse readAndPullLeft(y2)
              }

            nextY match {
              case Some(next) => go(next)
              case None       => y2
            }


        }
      }

      yy = go(yy)
    }

    // seems like we can`t get inside actor handle for actor itself so this is nasty hack for now
    var actor: Actor[Msg] = null

    // Actor that does the `main` job
    val a: Actor[Msg] = Actor.actor[Msg] {

      case Ready(Side.L, next: (Throwable \/ Step[Task, L])@unchecked) =>
        sl = Some(next)
        runWye(actor)

      case Ready(Side.R, next: (Throwable \/ Step[Task, R])@unchecked) =>
        sr = Some(next)
        runWye(actor)

      case Get(cb: ((Throwable \/ Seq[O]) => Unit)@unchecked) =>
        out match {
          case -\/(err@(-\/(t))) => cb(err)
          case -\/(\/-(Nil))     => out = \/-(cb)
          case -\/(ok@(\/-(hd))) => out = -\/(\/-(Nil)); cb(ok)
        }
        runWye(actor)
    }

    actor = a

    eval(Task.delay(pull(Side.L)(p1, actor))).drain ++
      eval(Task.delay(pull(Side.R)(p2, actor))).drain ++
      repeatEval(Task.async[Seq[O]](cb => actor ! Get(cb))).flatMap(emitAll)


  }


}
