package scalaz.stream

import scalaz.\/
import scalaz.stream.Process.Halt
import Cause._

/**
 * Defines termination cause for the process.
 * Cause is always wrapped in `Halt` and controls process flow.
 */
sealed trait Cause {
  /**
   * Produces a cause that was caused by `cause`
   * @param cause
   * @return
   */
  def causedBy(cause: Cause): Cause = {
    (this, cause) match {
      case (End, End)                 => End
      case (End, Kill)                => Kill
      case (Kill, End | Kill)         => Kill
      case (End | Kill, err@Error(_)) => err
      case (err@Error(_), End | Kill) => err
      case (Error(rsn1), Error(rsn2)) if rsn1 == rsn2 => this
      case (Error(rsn1), Error(rsn2)) => Error(CausedBy(rsn1, rsn2))
    }
  }

  /**
   * Converts cause to `Kill` or an `Error`
   * @return
   */
  def kill: EarlyCause = fold[EarlyCause](Kill)(identity)

  def fold[A](onEnd: => A)(f:(EarlyCause => A)) = this match {
    case End => onEnd
    case early:EarlyCause => f(early)
  }

  /**
   * Converts this termination cause to `Process.Halt`
   */
  def asHalt: Halt = this match {
    case End => Halt(End)
    case Error(Terminated(cause)) => Halt(cause)
    case cause => Halt(cause)
  }

  /**
   * Converts this cause to `java.lang.Throwable`
   */
  def asThrowable: Throwable = this match {
    case End => Terminated(End)
    case Kill => Terminated(Kill)
    case Error(rsn) => rsn
  }
}

object Cause {

  /**
   * A marker that is indicating Cause was terminating the stream EarlyCause,
   * either due to error, or being killed
   */
  sealed trait EarlyCause extends Cause

  object EarlyCause {
    def fromTaskResult[A](r:Throwable\/A):EarlyCause\/A =
      r.bimap(Error.apply,identity)
  }


  /**
   * Process terminated normally due to End of input.
   * That means items from Emit have been exhausted.
   */
  case object End extends Cause



  /**
   * Signals force-full process termination.
   * Process can be killed when merged (pipe,tee,wye,njoin) and other merging stream or
   * resulting downstream requested termination of process.
   * This shall cause process to run all cleanup actions and then terminate normally
   */
  case object Kill extends EarlyCause

  /**
   * Signals, that evaluation of last await resulted in error.
   *
   * If error is not handled, this will cause the process to terminate with supplier error.
   *
   * @param rsn Error thrown by last await.
   *
   */
  case class Error(rsn: Throwable) extends EarlyCause {
    override def toString: String = {
      s"Error(${rsn.getClass.getName}: ${rsn.getMessage}})"
    }
  }


  /**
   * Wrapper for Exception that was caused by other Exception during the
   * Execution of the Process
   */
  case class CausedBy(e: Throwable, cause: Throwable) extends Exception(cause) {
    override def toString = s"$e caused by: $cause"
    override def getMessage: String = toString
    override def fillInStackTrace(): Throwable = this
  }

  /**
   * wrapper to signal cause for termination.
   * This is useful when cause needs to be propagated out of process domain (i.e. Task)
   */
  case class Terminated(cause:Cause) extends Exception {
    override def fillInStackTrace(): Throwable = cause match {
      case End | Kill => this
      case Error(rsn) => rsn
    }
    override def toString: String = s"Terminated($cause)"
    override def getMessage: String = cause.toString
  }
}
