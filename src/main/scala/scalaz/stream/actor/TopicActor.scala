package scalaz.stream.actor

import scala.collection.immutable.{Iterable, Queue}
import scalaz.\/
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy}
import scalaz.stream.Process._


object TopicActor {

  sealed trait Msg[A, B]
  case class Publish[A, B](a: A, cb: (Throwable \/ Unit) => Unit) extends Msg[A, B]
  case class Fail[A, B](t: Throwable, cb: (Throwable \/ Unit) => Unit) extends Msg[A, B]

  case class Subscribe[A, B](cb: (Throwable \/ SubscriberRef[B]) => Unit) extends Msg[A, B]
  case class UnSubscribe[A, B](ref: SubscriberRef[B], cb: (Throwable \/ Unit) => Unit) extends Msg[A, B]
  case class GetOne[A, B](ref: SubscriberRef[B], cb: (Throwable \/ Seq[B]) => Unit) extends Msg[A, B]

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
   * @param p Process, that may transform A to another A when new `A` arrives.
   */
  def topic[A, B](p: Process1[A, B])(implicit S: Strategy): Actor[Msg[A, B]] = {

    var subs = List[SubscriberRefInstance[B]]()

    //just helper for callback
    val open : Throwable \/ Unit = right(())

    //left when this topic actor terminates or finishes
    var terminated : Throwable \/ Unit = open

    @inline def ready = terminated.isRight

    var state: Process1[A, B] = p

    val actor = Actor.actor[Msg[A, B]] {

      //publishes message in the topic when there is state process defined
      case Publish(a, cb) if ready =>
        val np = state.feed1(a)
        val (xb, next) = np.unemit
        if (xb.nonEmpty) {
          subs.foreach(_.publishAll(xb.toList))
          S(cb(open))
        }
        state = next

      //Gets the value of the reference
      //it wil register call back if there are no messages to be published
      case GetOne(ref: SubscriberRefInstance[B@unchecked], cb) if ready =>
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
        val subRef = new SubscriberRefInstance[B](left(Queue()))(S)
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

