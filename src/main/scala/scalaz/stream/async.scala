package scalaz.stream

import scalaz.{Functor, Apply, \/-, \/}
import scalaz.\/._
import scalaz.concurrent._
import java.util.concurrent.atomic._

import scalaz.stream.Process._
import scala.Some

trait async {
  import async.{Queue,Ref,Signal}

  /** 
   * Convert from an `Actor` accepting `message.queue.Msg[A]` messages 
   * to a `Queue[A]`. 
   */
  def actorQueue[A](actor: Actor[message.queue.Msg[A]]): Queue[A] =
    new Queue[A] {
      def enqueueImpl(a: A): Unit = actor ! message.queue.enqueue(a)
      def dequeueImpl(cb: (Throwable \/ A) => Unit): Unit = actor ! message.queue.Dequeue(cb)
      def fail(err: Throwable): Unit = actor ! message.queue.fail(err)
      def cancel: Unit = actor ! message.queue.cancel
      def close: Unit = actor ! message.queue.close
    }

  /** 
   * Convert from an `Actor` accepting `message.queue.Msg[A]` messages 
   * to a `Queue[A]`. 
   */
  def actorRef[A](actor: Actor[message.ref.Msg[A]]): Ref[A] =
    new Ref[A] {
      import message.ref._
      @volatile var init = false
      protected[stream] def set_(f: (Option[A]) => Option[A], cb: (\/[Throwable, Option[A]]) => Unit, old: Boolean): Unit =  {
        actor ! Set(f,cb,old)
        init = true
      }

      protected[stream] def get_(cb: (\/[Throwable, (Int,A)]) => Unit, onlyChanged: Boolean, last: Int) : Unit = 
        actor ! Get(cb,onlyChanged,last)
 
      protected[stream] def fail_(t: Throwable, cb: (Throwable) => Unit):Unit =  
        actor ! Fail(t,cb)

      def isSet = init
    }



  /** 
   * Create a new continuous signal which may be controlled asynchronously.
   * Signal may create multiple type of processes 
   * that are all controlled by single asynchronous reference
   */
  def signal[A](implicit S: Strategy = Strategy.DefaultStrategy): Signal[A] = {
    
    new Signal[A] { 
      
      val value: Ref[A] = ref[A](S)

      def checkStampChange:Process1[(Int,A),Boolean] = {
        def go(last:(Int,A)) : Process1[(Int,A),Boolean] = {
          await1[(Int,A)].flatMap ( next => emit(next != last) then go(next) )
        }
        await1[(Int,A)].flatMap(next=> emit(true) then go(next))
      }
      
      def changed =  value.toStampedSource |> checkStampChange 
       
      def discrete = value.toDiscreteSource
 
      def continuous = value.toSource
 
      def changes = value.toStampedDiscreteSource.map(_=>())
    }
     
    
  }
    
  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`, `q`. On calling `q.enqueue(a)`, 
   * unless another thread is already processing the elements 
   * in the queue, listeners on the queue will be run using the calling
   * thread of `q.enqueue(a)`, which means that enqueueing is not
   * guaranteed to take constant time. If this is not desired, use 
   * `queue` with a `Strategy` other than `Strategy.Sequential`.
   */
  def localQueue[A]: (Queue[A], Process[Task,A]) = 
    queue[A](Strategy.Sequential)

  /** 
   * Returns a continuous `Process` whose value can be set 
   * asynchronously using the returned `Ref`. Callbacks will be 
   * run in the calling thread unless another thread is already
   * reading from the `Ref`, so `set` is not guaranteed to take
   * constant time. If this is not desired, use `ref` with a
   * `Strategy` other than `Strategy.Sequential`.
   */
  def localRef[A]: Ref[A] = 
    ref[A](Strategy.Sequential)
  

  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`. See `async.Queue`. As long as the
   * `Strategy` is not `Strategy.Sequential`, enqueueing is 
   * guaranteed to take constant time, and consumers will be run on
   * a separate logical thread. Current implementation is based on 
   * `actor.queue`.
   */
  def queue[A](implicit S: Strategy = Strategy.DefaultStrategy): (Queue[A], Process[Task,A]) = 
    actor.queue[A] match { case (snk, p) => (actorQueue(snk), p) }

  /**
   * Returns a ref, that can create continuous process, that can be set 
   * asynchronously using the returned `Ref`.
   */
  def ref[A](implicit S: Strategy = Strategy.DefaultStrategy): Ref[A] = 
    actor.ref[A](S) match { case (snk, p) => actorRef(snk)}

  /** 
   * Convert an `Queue[A]` to a `Sink[Task, A]`. The `cleanup` action will be 
   * run when the `Sink` is terminated.
   */
  def toSink[A](q: Queue[A], cleanup: Queue[A] => Task[Unit] = (q: Queue[A]) => Task.delay {}): Process.Sink[Task,A] =
    io.resource(Task.now(q))(cleanup)(q => Task.delay { (a:A) => Task.now(q.enqueue(a)) }) 
}

object async extends async {

  implicit class QueueOps[A](val q: Queue[A]) {
    
  }
  
  trait Queue[A] {
    protected def enqueueImpl(a: A): Unit
    protected def dequeueImpl(cb: (Throwable \/ A) => Unit): Unit

    /** 
     * Asynchronously dequeue the next element from this `Queue`.
     * If no elements are currently available, the given callback
     * will be invoked later, when an element does become available,
     * or if an error occurs.
     */
    def dequeue(cb: (Throwable \/ A) => Unit): Unit = 
      dequeueImpl { r => sz.decrementAndGet; cb(r) }

    /**
     * Asynchronous trigger failure of this `Queue`. 
     */
    def fail(err: Throwable): Unit

    /** 
     * Halt consumers of this `Queue`, after allowing any unconsumed 
     * queued elements to be processed first. For immediate 
     * cancellation, ignoring any unconsumed elements, use `cancel`.  
     */
    def close: Unit

    /**
     * Halt consumers of this `Queue` immediately, ignoring any 
     * unconsumed queued elements.
     */
    def cancel: Unit

    /** 
     * Add an element to this `Queue` in FIFO order, and update the
     * size. 
     */
    def enqueue(a: A): Unit = {
      sz.incrementAndGet
      enqueueImpl(a)
    }
    private val sz = new AtomicInteger(0)

    /** 
     * Return the current number of unconsumed queued elements. 
     * Guaranteed to take constant time, but may be immediately
     * out of date.
     */
    def size = sz.get 

    /** 
     * Convert to a `Sink[Task, A]`. The `cleanup` action will be 
     * run when the `Sink` is terminated.
     */
    def toSink(cleanup: Queue[A] => Task[Unit] = (q: Queue[A]) => Task.delay {}): Sink[Task, A] = 
      async.toSink(this, cleanup)
  }

  trait Ref[A] {
     
   
    protected[stream] def set_(f:Option[A] => Option[A],
                               cb:(Throwable \/ Option[A]) => Unit =  (_) => (),
                               old:Boolean ) : Unit

    protected[stream] def get_(cb: (Throwable \/  (Int,A)) => Unit, onlyChanged:Boolean, last:Int) : Unit

    protected[stream] def fail_(t:Throwable, cb:Throwable => Unit = _ => ())
    

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
     * Sets the value inside this `ref`. If this is the first time the `Ref`
     * is `set`, this triggers evaluation of any `onRead` actions registered
     * with the `Ref`. If `Ref` is finished or `failed` this is no-op.  
     */
    def set(a: A): Unit =
      set_ (_ => Some(a), old = false)


    /**
     * Returns true, when this ref is set. 
     * Will return true as well when this `Ref` is `failed` or `finished`
     */
    def isSet: Boolean

    /**
     * Return a continuous stream which emits the current value in this `Ref`. 
     * Note that the `Ref` is left 'open'. If you wish to ensure that the value
     * cannot be used afterward, use the idiom 
     * `r.toSource onComplete Process.wrap(Task.delay(r.close)).drain`
     */
    def toSource: Process[Task,A] =
      Process.repeatWrap[Task,A](async.get)

    /**
     * Returns a discrete stream, which emits the current value of this `Ref`, 
     * but only when the `Ref` changes, except for very first emit, which is 
     * emitted immediately once run, or after `ref` is set for the fist time . 
     *  
     */
    def toDiscreteSource: Process[Task,A] = 
      toStampedDiscreteSource.map(_._2)

    /**
     * Unlike the `toSource` will emit values with their stamp.
     * @return
     */
    def toStampedSource: Process[Task,(Int,A)] =
      Process.repeatWrap[Task,(Int,A)](async.getStamped)


    /**
     * Discrete (see `toDiscreteSource`) variant of `toStampedSource`
     * @return
     */
    def toStampedDiscreteSource: Process[Task,(Int,A)] =  {
      /* The implementation here may seem a redundant a bit, but we need to keep
       * own serial number to make sure the Get events has own context for
       * every `toStampedDiscreteSource` process. 
       */
      def go(ser:Int, changed:Boolean): Process[Task,(Int,A)] =
        await[Task,(Int,A),(Int,A)](Task.async { cb => get_(cb,changed,ser) })(sa => emit(sa) ++ go(sa._1, true),halt, halt)

      go(0,false)
    }
   

    /**
     * Returns asynchronous version of this `Ref`. Modifications are performed
     * at the time when the resulting tasks are run. 
     *
     * If the `Ref` is in `finished` or `failed` state, tasks will fail with 
     * respective state (`End` or `Throwable`) as their cause, 
     * except for close and fail, that will never fail.
     * @return
     */
    lazy val  async : AsyncRef[A] = new AsyncRef[A] {protected val ref = Ref.this}
   

  }


  trait AsyncRef[A] {
    protected val ref:Ref[A]
    /**
     * Like a [[scalaz.stream.async.Ref.get]],
     * only the value is get asynchronously when the resulting task will be run
     */
    def get : Task[A] = Task.async[A](ref.get)

    /**
     * Like a [[scalaz.stream.async.Ref.get]],
     * only the value is get asynchronously when the resulting task will be run 
     * and contains also the stamp of current value 
     * @return
     */
    def getStamped :Task[(Int,A)] = Task.async[(Int,A)](ref.get_(_,false,0))

    /**
     * Like [[scalaz.stream.async.Ref.getAndSet]],
     * but unlike it it sets the value asynchronously when the resulting task is run 
     */
    def getAndSet(a:A) : Task[Option[A]] = Task.async[Option[A]](ref.getAndSet(a, _))

    /**
     * like [[scalaz.stream.async.Ref.compareAndSet]],
     * but will be executed asynchronously, when resulting task is run
     */
    def compareAndSet(f: Option[A] => Option[A]) : Task[Option[A]] = Task.async[Option[A]](ref.compareAndSet(f, _))

    /**
     * Like [[scalaz.stream.async.Ref.close]],  
     * but will be executed asynchronously, when resulting task is run
     */
    def close : Task[Unit] = fail(End)

    /**
     * Same as [[scalaz.stream.async.Ref.fail]],
     * but the operation is done asynchronously when the task is run. 
     */
    def fail(error:Throwable):Task[Unit] =
      Task.async[Unit] ( cb =>  ref.fail_(error,_=>cb(right(()))))

    /**
     * Same as [[scalaz.stream.async.Ref.set]],
     * only that it will be set when the resulting task is run 
     */
    def set(a:A) : Task[Unit] =
      compareAndSet(_=>Some(a)).map(_=>()) 

    /**
     * Same as [[scalaz.stream.async.Ref.isSet]],
     * but the operation is done asynchronously when the task is run. 
     * @return
     */
    def isSet: Task[Boolean] = Task.delay(ref.isSet)
  }
  
  /** 
   * A signal whose value may be set asynchronously. Provides continuous 
   * and discrete streams for responding to changes to this value. 
   */
  trait Signal[A]  {

    /** The value of this `Signal`. May be set asynchronously. */
    def value: Ref[A]

    /** 
     * Returns a continuous stream, indicating whether the value has changed. 
     * This will spike `true` once for each time the value ref was changed. 
     * 
     */
    def changed: Process[Task, Boolean]

    /** 
     * Returns the discrete version of this signal, updated only when `value`
     * is changed.  Value may changed several times between reads, but it is
     * guaranteed this will always get latest known value after any change. If you want
     * to be notified about every single change use `async.queue` for signalling. 
     */
    def discrete: Process[Task, A]

    /** 
     * Returns the continuous version of this signal, always equal to the 
     * current `A` inside `value`.
     */
    def continuous: Process[Task, A]

    /** 
     * Returns the discrete version of `changed`. Will emit `Unit` 
     * when the `value` is changed.
     */
    def changes: Process[Task, Unit]

    /** 
     * Keep the value of this `Signal` the same, but notify any listeners.
     */
    def refresh: Unit = value.modify(a => a)
  }
}
