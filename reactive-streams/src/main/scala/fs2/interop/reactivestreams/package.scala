package fs2
package interop

import cats.effect._
import org.reactivestreams._

/**
  * Implementation of the reactivestreams protocol for fs2
  *
  * @example {{{
  * scala> import fs2._
  * scala> import fs2.interop.reactivestreams._
  * scala> import cats.effect.{ContextShift, IO}
  * scala> import scala.concurrent.ExecutionContext
  * scala>
  * scala> implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  * scala>
  * scala> val upstream: Stream[IO, Int] = Stream(1, 2, 3).covary[IO]
  * scala> val publisher: StreamUnicastPublisher[IO, Int] = upstream.toUnicastPublisher
  * scala> val downstream: Stream[IO, Int] = publisher.toStream[IO]
  * scala>
  * scala> downstream.compile.toVector.unsafeRunSync()
  * res0: Vector[Int] = Vector(1, 2, 3)
  * }}}
  *
  * @see [[http://www.reactive-streams.org/]]
  */
package object reactivestreams {

  /**
    * Creates a lazy stream from an `org.reactivestreams.Publisher`.
    *
    * The publisher only receives a subscriber when the stream is run.
    */
  def fromPublisher[F[_]: ConcurrentEffect, A](p: Publisher[A]): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A](new Runner[F]))
      .flatMap(s => s.sub.stream(Sync[F].delay(p.subscribe(s))))

  implicit final class PublisherOps[A](val publisher: Publisher[A]) extends AnyVal {

    /** Creates a lazy stream from an `org.reactivestreams.Publisher` */
    def toStream[F[_]: ConcurrentEffect]: Stream[F, A] =
      fromPublisher(publisher)
  }

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) {

    /**
      * Creates a [[StreamUnicastPublisher]] from a stream.
      *
      * This publisher can only have a single subscription.
      * The stream is only ran when elements are requested.
      */
    def toUnicastPublisher(implicit F: ConcurrentEffect[F]): StreamUnicastPublisher[F, A] =
      StreamUnicastPublisher(stream, new Runner[F])
  }

  private[interop] implicit class RunnerSyntax[F[_]: ConcurrentEffect, A](fa: F[A]) {
    def unsafeRunAsync(runner: Runner[F]): Unit = 
      runner.unsafeRunAsync(fa)
  }
}
