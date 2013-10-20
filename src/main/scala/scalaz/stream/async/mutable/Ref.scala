package scalaz.stream.async.mutable

import scalaz.\/
import scalaz.\/._
import scalaz.concurrent._
import java.util.concurrent.atomic._

import scalaz.stream.Process
import scalaz.stream.Process._ 

trait Ref[A] { self =>

  /**
   * Get the current value of this `Ref`. If this
   * `Ref` has not been `set`, the callback will be invoked later.
   */
  def get(cb: (Throwable \/ A) => Unit): Unit = get_(r=>cb(r.map(_._2)),false,0)

  /**
   * Modify the current value of this `Ref`. If this `Ref`
   * has not been set, or is `finished` this has no effect. 
   */
  def modify(f: A => A): Unit =
    compareAndSet({ case Some(a) => Some(f(a)) ; case _ => None } , _ => ())

  /**
   * Sets the current value of this `Ref` and returns previous value of the `Ref`. If this `Ref`
   * has not been set, cb is invoked with `None` and value is set. If this Ref 
   * has been `finished` and is no longer valid this has no effect and cb will be called with `-\/(End)`
   *
   */
  def getAndSet(a:A, cb: (Throwable \/ Option[A]) => Unit): Unit  =
    set_({ case Some(ca) => Some(a); case _ => None } , cb, old = true)

  /**
   * Sets the current value of this `Ref` and invoke callback with result of `f`. 
   * If this `Ref` has not been set the input to `f` is None. 
   * Additionally if `f` returns None, its no-op and callback will be invoked with current value.
   * In case the `Ref` is finished will invoke callback with `-\/(End)` and will be no-op
   * If `Ref` is failed, callback is invoked with `-\/(ex)` where ex is reason for failed Ref.
   */
  def compareAndSet(f: Option[A] => Option[A],  cb: (Throwable \/ Option[A]) => Unit): Unit =
    set_({ old => f(old)} , cb, old = false)


  /**
   * Indicate that the value is no longer valid. Any attempts to `set` this
   * `Ref` after a `close` will be ignored. This `Ref` is `finished` from now on
   */
  def close: Unit  = fail(End)



  /**
   * Raise an asynchronous error for readers of this `Ref`. Any attempts to 
   * `set` this `Ref` after the `fail` are ignored. This `Ref` is `failed` from now on.  
   */
  def fail(error: Throwable): Unit =
    fail_(error)



  /**
   * Sets the value inside this `ref`.  
   */
  def set(a: A): Unit =
    set_ (_ => Some(a), old = false)


  /**
   * Returns true, when this ref is set. 
   * Will return true as well when this `Ref` is `failed` or `finished`
   */
  def isSet: Boolean

  def signal: Signal[A] = new Signal[A] {

    val value:Ref[A] = self

    val continuous: Process[Task,A] =
      Process.repeatEval[Task,A](Task.async[(Int,A)](self.get_(_,false,0)).map(_._2))

    val discrete: Process[Task,A] =  {
      /* The implementation here may seem a redundant a bit, but we need to keep
       * own serial number to make sure the Get events has own context for
       * every `toStampedDiscreteSource` process. 
       */
      def go(ser:Int, changed:Boolean): Process[Task,A] =
        await[Task,(Int,A),A](Task.async { cb => get_(cb,changed,ser) })(sa => emit(sa._2) ++ go(sa._1, true),halt, halt)

      go(0,false)
    }
  }

  ////
  //// PRIVATE

  private[stream] def set_(f:Option[A] => Option[A],
                             cb:(Throwable \/ Option[A]) => Unit =  (_) => (),
                             old:Boolean ) : Unit

  private[stream] def get_(cb: (Throwable \/  (Int,A)) => Unit, onlyChanged:Boolean, last:Int) : Unit

  private[stream] def fail_(t:Throwable, cb:Throwable => Unit = _ => ())

}
