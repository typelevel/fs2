package scalaz.stream.actor

import scalaz._
import scala.collection.immutable.Queue
import scalaz.concurrent.{Task, Strategy}
import scalaz.\/._
import scalaz.-\/
import scalaz.\/-
import scalaz.stream._
import scalaz.stream.Process.End

object message {

  private[actor] val okSignal = \/-(())

  object queue {
    trait Msg[A]
    case class Dequeue[A](callback: (Throwable \/ A) => Unit) extends Msg[A]
    case class Enqueue[A](a: A) extends Msg[A]
    case class Close[A](reason: Throwable, cancel: Boolean) extends Msg[A]

    def enqueue[A](a: A): Msg[A] =
      Enqueue(a)

    def dequeue[A](cb: A => Unit, onError: Throwable => Unit = t => ()): Msg[A] =
      Dequeue {
        case -\/(e) => onError(e)
        case \/-(a) => cb(a)
      }

    def close[A]: Msg[A] = fail(End,false)
    def cancel[A]: Msg[A] = fail(End,true)
    def fail[A](err: Throwable, cancel: Boolean = false): Msg[A] = Close(err, cancel)
  }

  object ref {
    sealed trait Msg[A]
    case class Set[A](f:Option[A] => Option[A], cb:(Throwable \/ Option[A]) => Unit, returnOld:Boolean) extends Msg[A]
    case class Get[A](callback: (Throwable \/ (Int,A)) => Unit,onChange:Boolean,last:Int) extends Msg[A]
    case class Fail[A](t:Throwable, callback:Throwable => Unit) extends Msg[A]
  }


}

