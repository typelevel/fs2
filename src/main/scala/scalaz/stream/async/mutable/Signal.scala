package scalaz.stream.async.mutable

import scalaz.stream.Cause._
import scalaz.\/
import scalaz.\/._
import scalaz.concurrent._
import scalaz.stream.Process._
import scalaz.stream._
import scalaz.stream.async.mutable
import java.util.concurrent.atomic.AtomicReference


/**
 * A signal whose value may be set asynchronously. Provides continuous
 * and discrete streams for responding to changes to this value.
 */
trait Signal[A] extends scalaz.stream.async.immutable.Signal[A] {


  /**
   * Returns sink that can be used to set this signal
   */
  def sink: Sink[Task, Signal.Msg[A]]


  /**
   * Asynchronously refreshes the value of the signal,
   * keep the value of this `Signal` the same, but notify any listeners.
   * If the `Signal` is not yet set, this is no-op
   */
  def refresh: Task[Unit] = compareAndSet(oa=>oa).map(_=>())

  /**
   * Asynchronously get the current value of this `Signal`
   */
  def get: Task[A]


  /**
   * Sets the value of this `Signal`.
   */
  def set(a: A): Task[Unit]

  /**
   * Asynchronously sets the current value of this `Signal` and returns previous value of the `Signal`.
   * If this `Signal` has not been set yet, the Task will return None and value is set. If this `Signal`
   * is `finished` Task will fail with `End` exception. If this `Signal` is `failed` Task will fail
   * with `Signal` failure exception.
   *
   */
  def getAndSet(a:A) : Task[Option[A]]

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
  def compareAndSet(f: Option[A] => Option[A]) : Task[Option[A]]

  /**
   * Indicate that the value is no longer valid. Any attempts to `set` or `get` this
   * `Signal` after a `close` will fail with `Terminated(End)` exception. This `Signal` is `finished` from now on.
   *
   * Running this task once the `Signal` is `failed` or `finished` is no-op and this task will not fail.
   *
   */
  def close : Task[Unit] = failWithCause(End)

  /**
   * Indicate that the value is no longer valid. Any attempts to `set` or `get` this
   * `Signal` after a `close` will fail with `Terminated(Kill)` exception. This `Signal` is `finished` from now on.
   *
   * Running this task once the `Signal` is `failed` or `finished` is no-op and this task will not fail.
   *
   */
  def kill: Task[Unit] = failWithCause(Kill)

  /**
   * Raise an asynchronous error for readers of this `Signal`. Any attempts to
   * `set` or `get` this `Ref` after the `fail` will result in task failing with `Terminated(Error(errr))`.
   * This `Signal` is `failed` from now on.
   *
   * Running this task once the `Signal` is `failed` or `finished` is no-op and this task will not fail.
   */
  def fail(error: Throwable): Task[Unit] = failWithCause(Error(error))



  private[stream] def failWithCause(c:Cause):Task[Unit]


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



  protected[async] def signalWriter[A]: Writer1[A,Msg[A],Nothing] = {
    def go(oa:Option[A]) : Writer1[A,Msg[A],Nothing] =
    receive1[Msg[A], A \/ Nothing] {
      case Set(a) => tell(a) fby go(Some(a))
      case CompareAndSet(f:(Option[A] => Option[A])@unchecked) =>
        val next = f(oa)
        next match {
          case Some(a) => tell(a) fby go(Some(a))
          case None => go(oa)
        }
    }
    go(None)
  }


  protected[async] def apply[A](source:Process[Task,Msg[A]], haltOnSource : Boolean)(implicit S: Strategy) : Signal[A] = {
    val topic = WriterTopic.apply[A,Msg[A],Nothing](signalWriter)(source, haltOnSource)
    new mutable.Signal[A] {
      def changed: Process[Task, Boolean] = topic.signal.changed
      def discrete: Process[Task, A] = topic.signal.discrete
      def continuous: Process[Task, A] = topic.signal.continuous
      def changes: Process[Task, Unit] = topic.signal.changes
      def sink: Sink[Task, Msg[A]] = topic.publish
      def get: Task[A] = discrete.take(1).runLast.flatMap {
        case Some(a) => Task.now(a)
        case None    => Task.fail(Terminated(End))
      }
      def getAndSet(a: A): Task[Option[A]] = {
        for {
          ref <- Task.delay(new AtomicReference[Option[A]](None))
          _ <- topic.publishOne(CompareAndSet[A](curr => curr.map{ca => ref.set(Some(ca)); a}))
        } yield ref.get()
      }
      def set(a: A): Task[Unit] = topic.publishOne(Set(a))
      def compareAndSet(f: (Option[A]) => Option[A]): Task[Option[A]] = {
        for {
          ref <- Task.delay(new AtomicReference[Option[A]](None))
          _ <- topic.publishOne(CompareAndSet[A]({ curr =>
            val r = f(curr)
            ref.set(r orElse curr)
            r
          }))
        } yield ref.get()
      }
      private[stream] def failWithCause(c: Cause): Task[Unit] = topic.failWithCause(c)
    }

  }


}
