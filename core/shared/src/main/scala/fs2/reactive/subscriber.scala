package fs2
package reactive

import fs2.util._
import fs2.util.syntax._

import org.reactivestreams.{Subscriber => RSubscriber, Publisher, Subscription}

/** Dequeues from an upstream publisher */
trait PublisherQueue[F[_], A] {

  /** receives a subscription from upstream */
  def onSubscribe(s: Subscription): F[Unit]

  /** receives next record from upstream */
  def onNext(a: A): F[Unit]

  /** receives error from upstream */
  def onError(t: Throwable): F[Unit] 
  
  /** called when upstream has finished sending records */
  def onComplete: F[Unit] 

  /** called when downstream has finished consuming records */
  def onFinalize: F[Unit] 

  /** producer for downstream */
  def dequeue: F[Attempt[Option[A]]]
}

object PublisherQueue {

  /** produces a stream from an upstream publisher */
  def fromPublisher[A](tag: String, chunkSize: Int, p: Publisher[A])(implicit AA: Async[Task]): Stream[Task, A] =
    Stream.eval(PublisherQueue[A](tag, p)).flatMap(s =>
      Stream.eval(s.dequeue).repeat.through(pipe.rethrow).unNoneTerminate.rechunkN(chunkSize).onFinalize(s.onFinalize)
    )

  //TODO: This needs to be tested against <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.0/README.md#specification">reactive specification</a>
  /** An implementation of a org.reactivestreams.Subscriber */
  final class Subscriber[A](sub: PublisherQueue[Task, A]) extends RSubscriber[A] {
    def onSubscribe(s: Subscription): Unit = sub.onSubscribe(s).unsafeRunAsync(_ => ())
    //TODO: unsafeRun is only present on the JVM.  
    def onNext(a: A): Unit = ??? //sub.onNext(a).unsafeRun()
    def onComplete(): Unit = ??? //sub.onComplete.unsafeRun()
    def onError(t: Throwable): Unit = sub.onError(t).unsafeRunAsync(_ => ())
  }

  def apply[A](tag: String, p: Publisher[A])(implicit AA: Async[Task]): Task[PublisherQueue[Task, A]] = {

    /** Represents the state of the PublisherQueue */
    sealed trait State

    /** No downstream requests have been made (the downstream stream has not been pulled on) */
    case object Uninitialized extends State

    /** The upstream publisher has received the subscriber, but not yet sent a subscription
      * 
      *  @req the first downstream request
      */
    case class FirstRequest(req: Async.Ref[Task, Attempt[Option[A]]]) extends State

    /** The subscriber has requested an element from upstream, but not yet received it
      * 
      * @sub the subscription to upstream
      * @req the request from downstream
      */
    case class PendingElement(sub: Subscription, req: Async.Ref[Task, Attempt[Option[A]]]) extends State

    /** No downstream requests are open
      * 
      * @sub the subscription to upstream
      */
    case class Idle(sub: Subscription) extends State

    /** The upstream publisher has completed successfully */
    case object Complete extends State

    /** Downstream finished before upstream completed.  The subscriber cancelled the subscription. */
    case object Cancelled extends State

    /** An error was received from upstream */
    case class Errored(err: Throwable) extends State

    AA.refOf[State](Uninitialized).map { qref =>
      new PublisherQueue[Task, A] {
        def onSubscribe(s: Subscription): Task[Unit] = qref.modify {
          case FirstRequest(req) =>
            PendingElement(s, req)
          case o =>
            o
        }.flatMap { c => c.previous match {
          case _ : FirstRequest => 
            AA.delay(s.request(1))
          case o => 
            AA.fail(new Error(s"received subscription in invalid state [$o]"))
        }}

        def onNext(a: A): Task[Unit] = qref.modify {
          case PendingElement(s, r) =>
            Idle(s)
          case o =>
            o
        }.flatMap { c => c.previous match {
          case PendingElement(s, r) => r.setPure(Attempt.success(Some(a)))
          case o => 
            AA.fail(new Error(s"received record [$a] in invalid state [$o]"))
        }}

        def onComplete(): Task[Unit] = qref.modify {
          case _ => 
            Complete
        }.flatMap { _.previous match {
          case PendingElement(sub, r) =>
            r.setPure(Attempt.success(None))
          case o => AA.pure(())
        }}

        def onError(t: Throwable): Task[Unit] = qref.modify {
          case _ => 
            Errored(t)
        }.flatMap { _.previous match {
          case PendingElement(sub, r) => AA.delay(sub.cancel()) >> r.setPure(Attempt.failure(t))
          case Idle(sub) => AA.delay(sub.cancel())
          case o => AA.pure(())
        }}


        def onFinalize: Task[Unit] = qref.modify {
          case PendingElement(_, _) | Idle(_) => 
            Cancelled
          case o => 
            o
        }.flatMap { _.previous match {
          case PendingElement(sub, r) => AA.delay {
            sub.cancel()
          } >> r.setPure(Attempt.success(None))
          case Idle(sub) => AA.delay {
            sub.cancel()
          }
          case o => AA.pure(())
        }}


        def dequeue: Task[Attempt[Option[A]]] = AA.ref[Attempt[Option[A]]].flatMap { r =>
          qref.modify {
            case Uninitialized =>
              FirstRequest(r)
            case Idle(sub) =>
              PendingElement(sub, r)
            case o => o
          }.flatMap(c => c.previous match {
            case Uninitialized => 
              AA.delay(p.subscribe(new Subscriber(this))).flatMap(_ => r.get)
            case Idle(sub) =>
              AA.delay(sub.request(1)).flatMap( _ => r.get)
            case Errored(err) =>
              AA.pure(Attempt.failure(err))
            case Complete =>
              AA.pure(Attempt.success(None))
            case FirstRequest(_) | PendingElement(_, _) | Cancelled =>
              AA.pure(Attempt.failure(new Error(s"received request in invalid state [${c.previous}]")))
          })
        }
      }
    }
  }
}
