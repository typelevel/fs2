package scalaz.stream.async.mutable

import scalaz.\/
import scalaz.\/._
import scalaz.concurrent._
import java.util.concurrent.atomic._

import scalaz.stream.Process
import scalaz.stream.Process._

/** 
 * A signal whose value may be set asynchronously. Provides continuous 
 * and discrete streams for responding to changes to this value. 
 */
trait Signal[A] extends scalaz.stream.async.immutable.Signal[A] { self =>

  /**
   * Returns sink that can be used to set this signal
   */
  def sink: Sink[Task,Signal.Msg[A]] = Process.repeatEval[Task,Signal.Msg[A] => Task[Unit]] (Task.now{(msg:Signal.Msg[A]) => 
    import Signal._
    msg match {
        case Set(a) =>  self.set(a)
        case CompareAndSet(f) => self.compareAndSet(f).map(_=>())
        case Fail(err) =>  self.fail(err)
      }
  })
  
  
  /** 
   * Asynchronously refreshes the value of the signal, 
   * keep the value of this `Signal` the same, but notify any listeners.
   * If the `Signal` is not yet set, this is no-op
   */
  def refresh: Task[Unit] = compareAndSet(oa=>oa).map(_=>())

  /**
   * Asynchronously get the current value of this `Signal`
   */
  def get : Task[A] = Task.async[A](value.get)


  /**
   * Sets the value of this `Signal`. 
   */
  def set(a:A) : Task[Unit] =  compareAndSet(_=>Some(a)).map(_=>())

  /**
   * Asynchronously sets the current value of this `Signal` and returns previous value of the `Signal`. 
   * If this `Signal` has not been set yet, the Task will return None and value is set. If this `Signal`
   * is `finished` Task will fail with `End` exception. If this `Signal` is `failed` Task will fail 
   * with `Signal` failure exception.
   *
   */
  def getAndSet(a:A) : Task[Option[A]] =  Task.async[Option[A]](value.getAndSet(a, _))

  /**
   * Asynchronously sets the current value of this `Signal` and returns new value os this `Signal`.
   * If this `Signal` has not been set yet, the Task will return None and value is set. If this `Signal`
   * is `finished` Task will fail with `End` exception. If this `Signal` is `failed` Task will fail 
   * with `Signal` failure exception.
   * 
   * Furthermore if `f` results in evaluating to None, this Task is no-op and will return current value of the 
   * `Signal`.
   * 
   */
  def compareAndSet(f: Option[A] => Option[A]) : Task[Option[A]] = Task.async[Option[A]](value.compareAndSet(f, _))

  /**
   * Indicate that the value is no longer valid. Any attempts to `set` or `get` this
   * `Signal` after a `close` will fail with `End` exception. This `Signal` is `finished` from now on.
   * 
   * Running this task once the `Signal` is `failed` or `finished` is no-op and this task will not fail. 
   */
  def close : Task[Unit] = fail(End)

  /**
   * Raise an asynchronous error for readers of this `Signal`. Any attempts to 
   * `set` or `get` this `Ref` after the `fail` will result in task failing with `error`. 
   * This `Signal` is `failed` from now on. 
   * 
   * Running this task once the `Signal` is `failed` or `finished` is no-op and this task will not fail. 
   */
  def fail(error:Throwable):Task[Unit] = Task.async[Unit] ( cb =>  value.fail_(error,_=>cb(right(()))))

  /** The value of this `Signal`. May be set asynchronously. */
  def value: Ref[A]
  
}


object Signal {

  sealed trait Msg[+A]

  /**
   * Sets the signal to given value 
   */
  case class Set[A](a:A) extends Msg[A]

  /**
   * Conditionally sets signal to given value. Acts similarly as `Signal.compareAndSet`
   */
  case class CompareAndSet[A](f:Option[A] => Option[A]) extends Msg[A]


  /**
   * Fails the current signal with supplied exception. Acts similarly as `Signal.fail`
   */
  case class Fail(err:Throwable) extends Msg[Nothing]

  /**
   * Special variant of Fail, that will close the signal. 
   */
  val Close = Fail(End)
  
}
