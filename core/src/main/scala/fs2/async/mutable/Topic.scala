package fs2.async.mutable

import fs2.Async.Change
import fs2._
import fs2.Stream._

/**
  * Asynchronous Topic.
  *
  * Topic allows you to distribute `A` published by arbitrary number of publishers to arbitrary number of subscribers.
  *
  * Topic has built-in back-pressure support implemented as maximum bound (`maxQueued`) that subscriber is allowed to enqueue.
  * Once that bound is hit, the publish may hold on until subscriber consumes some of its published elements .
  *
  * Additionally the subscriber has possibility to terminate whenever size of enqueued elements is over certain size
  * by using `subscribeSize`.
  *
  *
  */
trait Topic[F[_],A] {

  /**
    * Published any elements from source of `A` to this topic. This will stop publishing if the source has no elements
    * or, any subscriber is far behind the `maxQueued` limit.
    */
  def publish:Sink[F,A]


  /**
    * Publish one `A` to topic.
    *
    * This will wait until `A` is published to all subscribers.
    * If one of the subscribers is over the `maxQueued` limit, this will wait to complete until that subscriber processes
    * some of its elements to get room for this new. published `A`
    *
    * @param a
    * @return
    */
  def publish1(a:A):F[Unit]

  /**
    * Offers one `A` to topic.
    *
    * This will either publishes `A` to all its subscribers and return true, or this will return false publishing to None of them.
    * In case publish failed, this indicates that some of the subscribers was over `maxQueued` parameter.
    *
    * @param a
    * @return
    */
  def offer1(a:A):F[Boolean]


  /**
    * Subscribes to receive any published `A` to this topic.
    *
    * Always returns last `A` published first, and then any next `A` published.
    *
    * If the subscriber is over `maxQueued` bound of messages awaiting to be processed,
    * then publishers will hold into publishing to the queue.
    *
    */
  def subscribe(maxQueued:Int):Stream[F,A]

  /**
    * Subscribes to receive published `A` to this topic.
    *
    * Always returns last `A` published first, and then any next `A` available
    *
    * Additionally this emits current size of the queue of `A` for this subscriber allowing
    * you to terminate (or adjust) the subscription if subscriber is way behind the elements available.
    *
    * Note that queue size is approximate and may not be exactly the size when `a` was take.
    *
    * If the subscriber is over `maxQueued` bound of messages awaiting to be processed,
    * then publishers will hold into publishing to the queue.
    *
    */
  def subscribeSize(maxQueued:Int):Stream[F,(Int,A)]


  /**
    * Discrete stream of current active subscribers
    */
  def subscribers:Stream[F,Int]

}



object Topic {


  def apply[F[_], A](initial:A)(implicit F: Async[F]):F[Topic[F,A]] = {
    // Id identifying each subscriber uniquely
    class ID


    // State of each subscriber.
    sealed trait Subscriber

    // Subscriber awaits nest `A`
    case class Await(maxQueued:Int, ref:F.Ref[Chunk[A]]) extends Subscriber

    //Subscriber is running, next elements has to be enqueued in `q`
    case class Running(maxQueued:Int, q:Vector[A]) extends Subscriber

    // internal state of the topic
    // `last` is last value published to topic, or if nothing was published then this eq to initial
    // `ready` all subscribers that are ready to accept any published value
    // `full` subscribers that are full and will block any publishers to enqueue `a`
    //  `publishers` any publishers awaiting to be consumed
    case class State(
      last:A
      , ready:Map[ID, Subscriber]
      , full:Map[ID, Running]
      , publishers:Vector[(Chunk[A], F.Ref[Unit])]
    )




    F.map(F.refOf(State(initial,Map.empty, Map.empty, Vector.empty))) { (stateRef:F.Ref[State]) =>

      def publish0(chunk:Chunk[A]):F[Unit] = {

        ???
      }


      def appendChunk(s:State, chunk:Chunk[A]):(State, F[Unit]) = {
        val (ready, f) =
          s.ready.foldLeft((Map[ID, Subscriber](),F.pure(()))) {
            case ((m,f0), (id,Await(max,ref))) => (m + (id -> Running(max,Vector.empty))) -> F.bind(f0)(_ => F.setPure(ref)(chunk))
            case ((m,f0), (id,Running(max,q))) => (m + (id -> Running(max,q ++ chunk.toVector)) -> f0)
          }

        s.copy(ready = ready) -> f
      }


      def offer0(a:A):F[Boolean] = {
        F.bind(F.modify2(stateRef){ s =>
          if (s.full.nonEmpty) s -> F.pure(false)
          else {
            val (ns,f) = appendChunk(s, Chunk.singleton(a))
            ns -> F.map(f){_ => true}
          }
        }) { _._2 }
      }


      def subscribe0(maxQueued: Int, id:ID): Stream[F, A] = {
        def register:F[A] =
          F.bind(F.modify(stateRef){s => s.copy(ready =  s.ready + (id -> Running(maxQueued,Vector.empty)))}) { c =>
            F.pure(c.previous.last)
          }

        def empty(s: State,r:Running) = s.copy(ready = s.ready + (id -> r.copy(q = Vector.empty))) -> Some(Chunk.seq(r.q))

        def tryAwaits0(s:State):(State, Option[F[Chunk[A]]]) = ???

        def tryAwaits(c:Change[State]):F[Unit] = {
          if (c.previous.full.nonEmpty && c.now.full.isEmpty && c.now.publishers.nonEmpty) {
            F.bind(F.modify2(stateRef)(tryAwaits0)) {
              case (c0,Some(fa)) => ???
              case (c0, None) => F.pure(())
            }
          } else F.pure(())
        }

        def registerRef0(ref:F.Ref[Chunk[A]])(s: State):(State,Option[Chunk[A]]) = {
          def register :(State,Option[Chunk[A]]) = s.copy(ready = s.ready + (id -> Await(maxQueued, ref)), full = s.full - id) -> None

          s.ready.get(id) match {
            case Some(r:Running) =>
              if (r.q.nonEmpty) empty(s,r) else register
            case _ => register //impossible
          }
        }

        def registerRef:F[Chunk[A]] = {
          F.bind(F.ref[Chunk[A]]) { ref =>
            F.bind(F.modify2(stateRef)(registerRef0(ref))) {
              case (_, None) => F.get(ref)
              case (c, Some(chunk)) => F.map(tryAwaits(c))(_ => chunk)
            }
          }
        }


        def getNext0(s: State):(State,Option[Chunk[A]]) = {

          s.ready.get(id) match {
            case Some(r@Running(_,q)) => if (q.nonEmpty) empty(s,r) else s -> None
            case Some(Await(max,ref)) => s -> None // impossible
            case None =>
              s.full.get(id) match {
                case Some(r@Running(_,q)) => if (q.nonEmpty) empty(s,r) else s -> None
                case None => s -> None
              }
          }
        }

        def getNext:F[Chunk[A]] = {
          F.bind(F.modify2(stateRef)(getNext0)){
            case (_,None) => registerRef
            case (c,Some(chunk)) => F.map(tryAwaits(c))(_ => chunk)
          }

        }

        def unRegister:F[Unit] = {
          F.map(F.modify(stateRef) { s => s.copy(ready = s.ready - id, full = s.full - id) }){_ => () }
        }

        (eval(register) ++ repeatEval(getNext).flatMap(Stream.chunk)).onFinalize(unRegister)
      }




      new Topic[F,A] {
        def publish:Sink[F,A] = {
          pipe.chunks andThen { _.evalMap(publish0) }
        }
        def subscribers: Stream[F, Int] = {
          repeatEval(F.map(F.get(stateRef)){ s => s.ready.size + s.full.size })
        }
        def offer1(a: A): F[Boolean] = offer0(a)
        def publish1(a: A): F[Unit] = publish0(Chunk.singleton(a))
        def subscribe(maxQueued: Int): Stream[F, A] = subscribe0(maxQueued, new ID)
        def subscribeSize(maxQueued: Int): Stream[F, (Int, A)] = {
          val id = new ID
          def getSize:F[Int] =
            F.map(F.get(stateRef)){ s =>
              (s.ready.get(id).collect {
                case Running(_,q) => q.size
              } orElse s.full.get(id).map(_.q.size)).getOrElse(0)
            }
          subscribe0(maxQueued,id) flatMap { a =>
            eval(getSize) map { _ -> a }
          }
        }
      }
    }

  }


}
