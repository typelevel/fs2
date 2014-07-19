package scalaz.stream.async.mutable

import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process.{Cont, Halt}
import scalaz.stream._
import scalaz.stream.async.immutable
import scalaz.{-\/, \/, \/-}

/**
 * Like a `Topic`, but allows to specify `Writer1` to eventually `write` the state `W` or produce `O`
 * from arriving messages of `I` to this topic
 *
 */
trait WriterTopic[W, I, O] {



  /**
   * Gets publisher to this writer topic. There may be multiple publishers to this writer topic.
   */
  def publish: Sink[Task, I]

  /**
   * Gets subscriber from this writer topic. There may be multiple subscribers to this writer topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.
   *
   * If writer topic has `W` written, it will be first value written by this topic, following any `B` or `W` produced after.
   * @return
   */
  def subscribe: Writer[Task, W, O]

  /**
   * Subscribes to `O` values from this writer topic only.
   */
  def subscribeO: Process[Task, O]

  /** Subscribes to `W` values only from this Writer topic **/
  def subscribeW: Process[Task, W]

  /**
   * Provides signal of `W` values as they were emitted by Writer1 of this Writer topic
   **/
  def signal: scalaz.stream.async.immutable.Signal[W]


  /**
   * Publishes single `I` to this writer topic.
   */
  def publishOne(i: I): Task[Unit]

  /**
   * Will `close` this writer topic. Once `closed` all publishers and subscribers are halted via `End`.
   *
   * @return
   */
  def close: Task[Unit] = failWithCause(End)

  /**
   * Kills the writer topic. All subscribers and publishers are immediately terminated with `Kill` cause.
   * @return
   */
  def kill: Task[Unit] = failWithCause(Kill)

  /**
   * Will `fail` this writer topic. All subscribers and publishers are immediately terminated with `Error` cause.
   *
   * The resulting task is completed _after_ all publishers and subscribers finished
   *
   */
  def fail(err: Throwable): Task[Unit] = failWithCause(Error(err))


  private[stream] def failWithCause(c:Cause): Task[Unit]

}


private[stream] object WriterTopic {


  def apply[W, I, O](writer: Writer1[W, I, O])(source: Process[Task, I], haltOnSource: Boolean)(implicit S: Strategy): WriterTopic[W, I, O] = {
    import scalaz.stream.Util._
    sealed trait M

    case class Subscribe(sub: Subscription, cb: (Throwable \/ Unit) => Unit) extends M
    case class Ready(sub: Subscription, cb: (Throwable \/ Seq[W \/ O]) => Unit) extends M
    case class UnSubscribe(sub: Subscription, cb: (Throwable \/ Unit) => Unit) extends M
    case class Upstream(result: Cause \/ (Seq[I], Cont[Task,I])) extends M
    case class Publish(is: Seq[I], cb: Throwable \/ Unit => Unit) extends M
    case class Fail(cause: Cause, cb: Throwable \/ Unit => Unit) extends M

    class Subscription(var state: (Vector[W \/ O]) \/ ((Throwable \/ Seq[W \/ O]) => Unit)) {

      def getOrAwait(cb: ((Throwable \/ Seq[W \/ O]) => Unit)): Unit = state match {
        case -\/(v) if v.isEmpty => state = \/-(cb)
        case -\/(v) => state = -\/(Vector.empty); S(cb(\/-(v)))
        case \/-(_) => state = \/-(cb) //impossible
      }


      def publish(wo: Seq[W \/ O]): Unit = {
        val ns = state
        state match {
          case -\/(v)  => state = -\/(v fast_++ wo)
          case \/-(cb) => state = -\/(Vector.empty); S(cb(\/-(wo)))
        }
      }

      def close(cause: Cause): Unit = state match {
        case \/-(cb) =>
          state = -\/(Vector.empty)
          S(cb(-\/(Terminated(cause))))

        case _ => // no-op
      }

      def flushOrClose(cause: Cause, cb: (Throwable \/ Seq[W \/ O]) => Unit): Unit = state match {
        case -\/(v) if v.isEmpty => state = \/-(cb); close(cause)
        case -\/(v)              => getOrAwait(cb)
        case \/-(cb)             => //impossible
      }

    }

    ///////////////////////

    var subscriptions: Vector[Subscription] = Vector.empty

    //last memorized `W`
    var lastW: Option[W] = None

    //contains an reason of termination
    var closed: Option[Cause] = None

    // state of upstream. Upstream is running and left is interrupt, or upstream is stopped.
    var upState: Option[Cause \/ (EarlyCause => Unit)] = None

    var w: Writer1[W, I, O] = writer

    def fail(cause: Cause): Unit = {
      closed = Some(cause)
      upState.collect { case \/-(interrupt) => interrupt(Kill)}
      val (wos,next) = w.disconnect(cause.kill).unemit
      w = next
      if (wos.nonEmpty) subscriptions.foreach(_.publish(wos))
      subscriptions.foreach(_.close(cause))
      subscriptions = Vector.empty
    }

    def publish(is: Seq[I]) = {
      debug(s"@@@ PUB IS: $is W: $w ")
      process1.feed(is)(w).unemit match {
        case (wos, next) =>
          w = next
          lastW = wos.collect({ case -\/(w) => w }).lastOption orElse lastW
          if (wos.nonEmpty) subscriptions.foreach(_.publish(wos))
          next match {
            case hlt@Halt(rsn) =>  fail(rsn)
            case _             =>  //no-op
          }
      }
    }

    var actor: Actor[M] = null

    def getNext(p: Process[Task, I]) = {
      upState= Some(\/-(
        p.runAsync({ actor ! Upstream(_) })(S)
      ))
    }

    actor = Actor[M](m => {
      debug(s">>> IN:  m: $m  | sub: $subscriptions | lw: $lastW | clsd: $closed | upState: $upState | wrtr: $writer | hOs: $haltOnSource")
      closed.fold(m match {
        case Subscribe(sub, cb) =>
          subscriptions = subscriptions :+ sub
          lastW.foreach(w => sub.publish(Seq(-\/(w))))
          S(cb(\/-(())))
          if (upState.isEmpty) {
            publish(Nil) // causes un-emit of `w`
            getNext(source)
          }

        case UnSubscribe(sub, cb) =>
          subscriptions = subscriptions.filterNot(_ == sub)
          S(cb(\/-(())))


        case Ready(sub, cb) =>
          debug(s"||| RDY $sub | ${sub.state }")
          sub.getOrAwait(cb)

        case Upstream(-\/(rsn)) =>
          if (haltOnSource || rsn != End) fail(rsn)
          upState = Some(-\/(rsn))

        case Upstream(\/-((is, cont))) =>
          publish(is)
          getNext(Util.Try(cont.continue))

        case Publish(is, cb) =>
          publish(is)
          S(cb(\/-(())))

        case Fail(rsn, cb) =>
          fail(rsn)
          upState.collect { case \/-(interrupt) => interrupt(Kill) }
          S(cb(\/-(())))

      })(rsn => m match {
        case Subscribe(_, cb)         => S(cb(-\/(Terminated(rsn))))
        case UnSubscribe(_, cb)       => S(cb(\/-(())))
        case Ready(sub, cb)           => sub.flushOrClose(rsn,cb)
        case Publish(_, cb)           => S(cb(-\/(Terminated(rsn))))
        case Fail(_, cb)              => S(cb(\/-(())))
        case Upstream(-\/(_))         => //no-op
        case Upstream(\/-((_, cont))) => S((Halt(Kill) +: cont).runAsync(_ => ()))
      })
    })(S)


    new WriterTopic[W, I, O] {
      def publish: Sink[Task, I] = Process.constant(publishOne _)
      def publishOne(i: I): Task[Unit] = Task.async { cb => actor ! Publish(Seq(i), cb) }
      def failWithCause(cause: Cause): Task[Unit] = Task.async { cb => actor ! Fail(cause, cb) }

      def subscribe: Writer[Task, W, O] = Process.suspend {

        val subscription = new Subscription(-\/(Vector.empty[W \/ O]))
        val register = Task.async[Unit] { cb => actor ! Subscribe(subscription, cb) }
        val unRegister = Task.async[Unit] { cb => actor ! UnSubscribe(subscription, cb) }
        val oneChunk = Task.async[Seq[W \/ O]] { cb => actor ! Ready(subscription, cb) }
        (Process.eval_(register) ++ Process.repeatEval(oneChunk).flatMap(Process.emitAll))
        .onComplete(Process.eval_(unRegister))
      }


      def subscribeO: Process[Task, O] = subscribe.collect { case \/-(o) => o }
      def subscribeW: Process[Task, W] = subscribe.collect { case -\/(w) => w }
      def signal: immutable.Signal[W] = new immutable.Signal[W] {
        def changes: Process[Task, Unit] = discrete.map(_=>())

        def continuous: Process[Task, W] =
          discrete.once.flatMap {
            Process.emit(_) ++ continuous
          }

        def discrete: Process[Task, W] = subscribeW

        def changed: Process[Task, Boolean] =
          discrete.map(_ => true)
          .wye(Process.repeatEval(Task.now(false)))(wye.mergeHaltL)
      }
    }
  }

}
