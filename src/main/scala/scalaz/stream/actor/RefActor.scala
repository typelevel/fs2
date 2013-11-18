package scalaz.stream.actor

import scalaz.concurrent.{Task, Actor, Strategy}
import scalaz.stream.Process
import scalaz.{-\/, \/-, \/}
import scalaz.\/._
import scala.Some
import scalaz.stream.Process.End


trait RefActor {

  /**
   * Returns a continuous `Process` whose value can be set
   * asynchronously using the returned `Actor`.
   *
   * `message.ref.Set(a,cb)` eventually sets the value of the stream,
   * `message.ref.Fail(e)`   terminates the stream with the given error,
   *                         or closes stream when error is End,
   * `message.ref.Get(cb)`   gets current value of ref via callback
   *                         if passed with `true` will get current value only
   *                         when supplied serial is different from current serial
   *
   *
   * process will self-terminate if:
   *
   * - Fail(End) is received      as normal termination
   * - Fail(err) is received      as termination with err  as failure
   * - Set fails                  as termination with that `set` failure
   * - Get callback fails         as termination with that `get` failure
   *
   */
  def ref[A](implicit S: Strategy): (Actor[message.ref.Msg[A]], Process[Task, A]) = {
    import message.ref._
    @volatile var ref: Throwable \/ Option[A] = right(None)
    @volatile var ser = 0

    @inline def done = ref.isLeft


    def set(r:Throwable \/ Option[A] ) = {
      ser = ser + 1
      ref = r
    }

    @volatile var listeners: Vector[(Throwable \/ (Int,A)) => Unit] = Vector()

    /** publishes to the listeners waiting on first `Set` or on any change of `ser`
      * If, any of the `callbacks` fail,  will fail and stop this reference as well
      * Listeners are executed on different threads
      */
    def publishAndClear = {
      if (listeners.nonEmpty) {
        ref.fold(
          l =  t =>  listeners.foreach (lst => S(lst(left(t))))
          , r = oa => {
            val cSer = ser //stabilize ser
            oa.map (aa => listeners.foreach(lst => S(lst(right(cSer,aa)))))
          }
        )
        listeners = Vector()
      }
    }

    /**
     * Callbacks the `Get` when the value was set .
     * If any callback will result in exception, this ref will fail
     * @param cb
     */
    def callBackOrListen(cb:(Throwable \/ (Int,A)) => Unit) =
      ref match {
        case \/-(Some(aa)) =>
          val cSer = ser
          S(cb(right((cSer,aa))))
        case \/-(None) => listeners = listeners :+ cb
        case -\/(err) => S(cb(left(err)))
      }



    val actor: Actor[Msg[A]] = Actor.actor {

      //eventually sets the value based on outcome of `f` and then makes
      //callback with new, modified reference or old Reference.
      case Set(f,cb,returnOld) if ! done =>
        val old = ref

        def callBackOnSet =   {
          val cref = ref //make the current ref stable for async callbacks that are run lazily on threads
          if (returnOld) {
            S(cb(cref.map(_=>old.toOption.flatten)))
          } else {
            S(cb(cref))
          }
        }


        fromTryCatch(f(ref.toOption.flatten)).fold(
          l => { set(left(l)); callBackOnSet; publishAndClear},
          r => r match {
            case Some(a) =>
              set(right(Some(a))); callBackOnSet; publishAndClear
            case None =>
              callBackOnSet
          }
        )



      //gets the current value of ref.
      // If ref is not set yet will register for later callback
      case Get(cb,false,_) if ! done =>
        callBackOrListen(cb)

      //Gets the current value only if the supplied ser
      //is different from current. Otherwise will wait for it
      //to change before invoking cb
      //If the ref is not set, it waits till it is set
      case Get(cb,true,last) if ! done =>
        if (ser != last)
          callBackOrListen(cb)
        else
          listeners = listeners :+ cb


      //fails or closes (when t == End) the ref
      case Fail(t,cb) if !done =>
        set(left(t))
        S(cb(t))
        publishAndClear

      //fallback
      //issues any callbacks when ref is failed or closed to prevent deadlock
      //todo: we may probably further optimize it for having same callback types here..
      case Get(cb,_,_) =>
        val cRef = ref
        S(cb(cRef.fold(l=>left(l),oa=>left(End))))
      case Set(_,cb,_) =>
        val cRef = ref
        S(cb(cRef.fold(l=>left(l),oa=>left(End))))
      case Fail(_,cb) =>
        val cRef = ref
        S(cb(cRef.fold(l=>l,oa=>End)))

    }

    ///
    val process = Process.repeatEval[Task,A] {
      Task.async[A] { cb => actor ! Get(sa=> {  cb(sa.map(_._2)) },false,0) }
    }
    (actor, process)
  }

  /**
   * Like `ref`, but runs the actor locally, on whatever thread sends it messages.
   */
  def localVariable[A]: (Actor[message.ref.Msg[A]], Process[Task,A]) =
    ref(Strategy.Sequential)

}
