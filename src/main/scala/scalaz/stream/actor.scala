package scalaz.stream

import scalaz._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.\/._

import collection.immutable.Queue
import scalaz.stream.Process.End
import scalaz.stream.async.immutable

trait actor {

  /**
   * Returns a discrete `Process` stream that can be added to or
   * halted asynchronously by sending the returned `Actor` messages.
   *
   * `message.queue.enqueue(a)` adds an element to the stream in FIFO order,
   * `message.queue.close` terminates the stream,
   * `message.queue.cancel` terminates the stream immediately, ignoring queued messages,
   * `message.queue.fail(e)` terminates the stream with the given error, and
   * `message.queue.fail(e,true)` terminates the stream with the given error, ignoring queued messages.
   *
   * Note that the memory usage of the actor can grow unbounded if
   * `enqueue` messages are sent to the actor faster than
   * they are dequeued by whatever consumes the output `Process`.
   * Use the `message.queue.size` message to asynchronously check the
   * queue size and throttle whatever is feeding the actor messages.
   */
  def queue[A](implicit S: Strategy): (Actor[message.queue.Msg[A]], Process[Task, A]) = {
    import message.queue._
    var q: Queue[Throwable \/ A] \/ Queue[(Throwable \/ A) => Unit] = left(Queue())
    // var q = Queue[Throwable \/ A]()
    var done = false
    val a: Actor[Msg[A]] = Actor.actor {
      case Enqueue(a) if !done => q match {
        case -\/(ready) => 
          q = left(ready.enqueue(right(a)))
        case \/-(listeners) => 
          if (listeners.isEmpty) q = left(Queue(right(a))) 
          else {
            val (cb, l2) = listeners.dequeue
            q = if (l2.isEmpty) left(Queue()) else right(l2)
            cb(right(a)) 
          }
      }
      case Dequeue(cb) => q match {
        case -\/(ready) =>
          if (ready.isEmpty) q = right(Queue(cb))
          else {
            val (a, r2) = ready.dequeue
            cb(a)
            q = left(r2) 
          }
        case \/-(listeners) => q = right(listeners.enqueue(cb))
      }
      case Close(cancel) if !done => q match {
        case -\/(ready) => 
          if (cancel) q = left(Queue(left(Process.End)))
          else { q = left(ready.enqueue(left(Process.End))) }
          done = true
        case \/-(listeners) =>
          val end = left(Process.End)
          listeners.foreach(_(end)) 
          q = left(Queue(end)) 
      }
      case Fail(e,cancel) if !done => q match {
        case -\/(ready) => 
          if (cancel) q = left(Queue(left(e)))
          else q = left(ready.enqueue(left(e)))
          done = true
        case \/-(listeners) => 
          val end = left(e)
          listeners.foreach(_(end))
          q = left(Queue(end))
      } 
      case _ => ()
    }
    val p = Process.repeatEval { Task.async[A] { cb => a ! Dequeue(cb) } }
    (a, p)
  }

  /**
   * Like `queue`, but runs the actor locally, on whatever thread sends it messages.
   */
  def localQueue[A]: (Actor[message.queue.Msg[A]], Process[Task,A]) = 
    queue(Strategy.Sequential)

  /**
   * Like `ref`, but runs the actor locally, on whatever thread sends it messages.
   */
  def localVariable[A]: (Actor[message.ref.Msg[A]], Process[Task,A]) = 
    ref(Strategy.Sequential)

  /** Convert an `Actor[A]` to a `Sink[Task, A]`. */
  def toSink[A](snk: Actor[A]): Process[Task, A => Task[Unit]] =
    Process.repeatEval { Task.now { (a: A) => Task.delay { snk ! a } } }

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
   * Return actor and signal that forms the topic. Each subscriber is identified by `SubscriberRef` and have 
   * its own Queue to keep messages that enqueue when the subscriber is processing the emitted
   * messages from publisher. 
   * 
   * There may be one or more publishers to single topic. 
   * 
   * Messages that are processed : 
   * 
   * message.topic.Publish      - publishes single message to topic
   * message.topic.Fail         - `fails` the topic. If the `End` si passed as cause, this topic is `finished`.
   * 
   * message.topic.Subscribe    - Subscribes single subscriber and starts collecting messages for it
   * message.topic.UnSubscribe  - Un-subscribes subscriber
   * message.topic.Get          - Registers callback or gets messages in subscriber`s queue 
   * 
   * Signal is notified always when the count of subscribers changes, and is set with very first subscriber
   * 
   */
  def topic[A](implicit S:Strategy) :(Actor[message.topic.Msg[A]]) = {
    import message.topic._
    
    var subs = List[SubscriberRefInstance[A]]()

    //just helper for callback
    val open : Throwable \/ Unit = right(())
    
    //left when this topic actor terminates or finishes
    var terminated : Throwable \/ Unit = open
    
    @inline def ready = terminated.isRight
    
    
    val actor =  Actor.actor[Msg[A]] {

      //Publishes message in the topic
      case Publish(a,cb) if ready =>
         subs.foreach(_.publish(a))   
         S(cb(open))

      //Gets the value of the reference 
      //it wil register call back if there are no messages to be published
      case Get(ref:SubscriberRefInstance[A@unchecked],cb) if ready =>
         ref.get(cb)                 

      //Stops or fails this topic  
      case Fail(err, cb) if ready =>
        subs.foreach(_.fail(err))
        subs = Nil
        terminated = left(err)
        S(cb(terminated))
        
      
      // Subscribes subscriber
      // When subscriber terminates it MUST send un-subscribe to release all it's resources
      case Subscribe(cb) if ready =>
        val subRef = new SubscriberRefInstance[A](left(Queue()))(S)
        subs = subs :+ subRef
        S(cb(right(subRef)))

      // UnSubscribes the subscriber. 
      // This will actually un-subscribe event when this topic terminates  
      // will also emit last collected data  
      case UnSubscribe(subRef, cb) => 
         subs = subs.filterNot(_ == subRef)
         S(cb(terminated))


      ////////////////////  
      // When the topic is terminated or failed 
      // The `terminated.left` is safe from here  
      
      case Publish(_,cb) => S(cb(terminated))

      case Get(ref:SubscriberRefInstance[A@unchecked],cb) => ref.flush(cb,terminated)

      case Fail(_,cb) =>  S(cb(terminated))

      case Subscribe(cb) => S(cb(terminated.bimap(t=>t,r=>sys.error("impossible"))))

      
    }
    actor
  }
  
  
  
}

object actor extends actor

object message {

  object queue {
    trait Msg[A]
    case class Dequeue[A](callback: (Throwable \/ A) => Unit) extends Msg[A]
    case class Enqueue[A](a: A) extends Msg[A]
    case class Fail[A](error: Throwable, cancel: Boolean) extends Msg[A]
    case class Close[A](cancel: Boolean) extends Msg[A]

    def enqueue[A](a: A): Msg[A] =
      Enqueue(a)

    def dequeue[A](cb: A => Unit, onError: Throwable => Unit = t => ()): Msg[A] =
      Dequeue {
        case -\/(e) => onError(e)
        case \/-(a) => cb(a)
      }

    def close[A]: Msg[A] = Close[A](false)
    def cancel[A]: Msg[A] = Close[A](true)
    def fail[A](err: Throwable, cancel: Boolean = false): Msg[A] = Fail(err, cancel)
  }

  object ref {
    sealed trait Msg[A]   
    case class Set[A](f:Option[A] => Option[A], cb:(Throwable \/ Option[A]) => Unit, returnOld:Boolean) extends Msg[A]
    case class Get[A](callback: (Throwable \/ (Int,A)) => Unit,onChange:Boolean,last:Int) extends Msg[A] 
    case class Fail[A](t:Throwable, callback:Throwable => Unit) extends Msg[A] 
  }
  
  
  object topic {
    sealed trait Msg[A]
    case class Publish[A](a:A, cb:(Throwable \/ Unit) => Unit) extends Msg[A]
    case class Fail[A](t:Throwable, cb:(Throwable \/ Unit) => Unit) extends Msg[A]
    
    case class Subscribe[A](cb:(Throwable \/ SubscriberRef[A]) => Unit) extends Msg[A]
    case class UnSubscribe[A](ref:SubscriberRef[A], cb:(Throwable \/ Unit) => Unit) extends Msg[A]
    case class Get[A](ref:SubscriberRef[A], cb: (Throwable \/ Seq[A]) => Unit) extends Msg[A]

    
    //For safety we just hide the mutable functionality from the ref which we passing around
    sealed trait SubscriberRef[A] 

    
    // all operations on this class are guaranteed to run on single thread, 
    // however because actor`s handler closure is not capturing `cbOrQueue`
    // It must be tagged volatile
    final class SubscriberRefInstance[A](@volatile var cbOrQueue : Queue[A] \/  ((Throwable \/ Seq[A]) => Unit))(implicit S:Strategy) 
      extends SubscriberRef[A] {

      //Publishes to subscriber or enqueue for next `Get`
      def publish(a:A)  = 
        cbOrQueue = cbOrQueue.fold(
         q => left(q.enqueue(a))
         , cb => {
            S(cb(right(List(a))))
            left(Queue())
          }
        )
         
      //fails the current call back, if any
      def fail(e:Throwable) = cbOrQueue.map(cb=>S(cb(left(e))))

      //Gets new data or registers call back
      def get(cb:(Throwable \/ Seq[A]) => Unit) = 
        cbOrQueue = cbOrQueue.fold(
         q =>  
           if (q.isEmpty) {
             right(cb)
           } else {
             S(cb(right(q)))
             left(Queue())
           }
         , cb => {
            // this is invalid state cannot have more than one callback
            // we will fail this new callback
            S(cb(left(new Exception("Only one callback allowed"))))
            cbOrQueue
          }
        )
      
      //Fails the callback, or when something in Q, flushes it to callback
      def flush(cb:(Throwable \/ Seq[A]) => Unit, terminated: Throwable \/ Unit) =
        cbOrQueue = cbOrQueue.fold(
         q => {
           if (q.isEmpty) cb(terminated.map(_=>Nil)) else cb(right(q)) 
           left(Queue())
         }
         , cb => {
            S(cb(left(new Exception("Only one callback allowed"))))
            cbOrQueue
          }  
            
        )
    }
    
  }
}
