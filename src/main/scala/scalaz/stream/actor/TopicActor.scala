package scalaz.stream.actor

import scala.collection.immutable.{Iterable, Queue}
import scalaz.{\/-, -\/, \/}
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy}
import scalaz.stream.Process._


object TopicActor {

  sealed trait Msg[S,A,B]
  case class Publish[S,A,B](a: A, cb: (Throwable \/ Unit) => Unit) extends Msg[S,A,B]
  case class Fail[S,A,B](t: Throwable, cb: (Throwable \/ Unit) => Unit) extends Msg[S,A,B]

  case class Subscribe[S,A,B](cb: (Throwable \/ SubscriberRef[S \/ B]) => Unit) extends Msg[S,A,B]
  case class UnSubscribe[S,A,B](ref: SubscriberRef[S \/ B], cb: (Throwable \/ Unit) => Unit) extends Msg[S,A,B]
  case class GetOne[S,A,B](ref: SubscriberRef[S \/ B], cb: (Throwable \/ Seq[S \/ B]) => Unit) extends Msg[S,A,B]

  //For safety we just hide the mutable functionality from the ref which we passing around
  sealed trait SubscriberRef[A]


  // all operations on this class are guaranteed to run on single thread,
  // however because actor`s handler closure is not capturing `cbOrQueue`
  // It must be tagged volatile
  final class SubscriberRefInstance[A](@volatile var cbOrQueue: Queue[A] \/ ((Throwable \/ Seq[A]) => Unit))(implicit S: Strategy)
    extends SubscriberRef[A] {

    //Publishes to subscriber or enqueue for next `Get`
    def publish(a: A) =
      cbOrQueue = cbOrQueue.fold(
        q => left(q.enqueue(a))
        , cb => {
          S(cb(right(List(a))))
          left(Queue())
        }
      )

    // like publish, only optimized for seq
    def publishAll(xa: Iterable[A]) =
      cbOrQueue = cbOrQueue.fold(
        q => left(q.enqueue(xa))
        , cb => {
          S(cb(right(xa.toSeq)))
          left(Queue())
        }
      )


    //fails the current call back, if any
    def fail(e: Throwable) = cbOrQueue.map(cb => S(cb(left(e))))

    //Gets new data or registers call back
    def get(cb: (Throwable \/ Seq[A]) => Unit) =
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
    def flush(cb: (Throwable \/ Seq[A]) => Unit, terminated: Throwable \/ Unit) =
      cbOrQueue = cbOrQueue.fold(
        q => {
          if (q.isEmpty) cb(terminated.map(_ => Nil)) else cb(right(q))
          left(Queue())
        }
        , cb => {
          S(cb(left(new Exception("Only one callback allowed"))))
          cbOrQueue
        }

      )
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
   * message.topic.GetOne       - Registers callback or gets messages in subscriber's queue
   *
   *
   * @param p Writer1, that may transform A to another B when new `A` arrives. It may also produce `S` that will
   *          get memoized and will be emitted to every subscriber as their first message
   */
  def topic[S,A,B](p: Writer1[S,A,B])(implicit S: Strategy): Actor[Msg[S,A,B]] = {

    var subs = List[SubscriberRefInstance[S \/ B]]()

    //just helper for callback
    val open : Throwable \/ Unit = right(())

    //left when this topic actor terminates or finishes
    var terminated : Throwable \/ Unit = open

    @inline def ready = terminated.isRight

    var state: Writer1[S,A,B] = p
    var lastW: Option[S] = None

    def fail(err:Throwable) = {
      subs.foreach(_.fail(err))
      subs = Nil
      terminated = left(err)
    }

    val actor = Actor.actor[Msg[S,A,B]] {

      //publishes message in the topic when there is state process defined
      case Publish(a, cb) if ready =>
        try {
          val ns = state.feed1(a)
          val (xb, next) = ns.unemit
          xb.collect{ case -\/(s) => s}.lastOption.map {
            s => lastW = Some(s)
          }
          if (xb.nonEmpty) {
            subs.foreach(_.publishAll(xb.toList))
            S(cb(open))
          }
          state = next
        } catch {
          case t : Throwable =>
            terminated = -\/(t)
            fail(t)
        }

      //Gets the value of the reference
      //it wil register call back if there are no messages to be published
      case GetOne(ref: SubscriberRefInstance[S\/B]@unchecked, cb) if ready =>
       ref.get(cb)

      //Stops or fails this topic
      case Fail(err, cb) if ready =>
        fail(err)
        S(cb(terminated))


      // Subscribes subscriber
      // When subscriber terminates it MUST send un-subscribe to release all it's resources
      case Subscribe(cb) if ready =>
        val q =  lastW.map(s=>Queue(-\/(s))).getOrElse(Queue())
        val subRef = new SubscriberRefInstance[S\/B](left(q))(S)
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

      case GetOne(ref: SubscriberRefInstance[B@unchecked], cb) => ref.flush(cb, terminated)

      case Fail(_,cb) =>  S(cb(terminated))

      case Subscribe(cb) => S(cb(terminated.bimap(t=>t,r=>sys.error("impossible"))))


    }
    actor
  }


}

