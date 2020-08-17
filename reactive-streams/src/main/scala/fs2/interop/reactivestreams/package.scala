package fs2
package interop

import cats.effect._
import cats.effect.implicits._
import cats.effect.unsafe.IORuntime
import org.reactivestreams._

/**
  * Implementation of the reactivestreams protocol for fs2
  *
  * @example {{{
  * scala> import fs2._
  * scala> import fs2.interop.reactivestreams._
  * scala> import cats.effect.IO, cats.effect.unsafe.implicits.global
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
  def fromPublisher[F[_]: Effect, A](p: Publisher[A])(implicit ioRuntime: IORuntime): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A])
      .flatMap(s => s.sub.stream(Sync[F].delay(p.subscribe(s))))

  implicit final class PublisherOps[A](val publisher: Publisher[A]) extends AnyVal {

    /** Creates a lazy stream from an `org.reactivestreams.Publisher` */
    def toStream[F[_]: Effect](implicit ioRuntime: IORuntime): Stream[F, A] =
      fromPublisher(publisher)
  }

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) {

    /**
      * Creates a [[StreamUnicastPublisher]] from a stream.
      *
      * This publisher can only have a single subscription.
      * The stream is only ran when elements are requested.
      */
    def toUnicastPublisher(implicit
        F: Effect[F],
        ioRuntime: IORuntime
    ): StreamUnicastPublisher[F, A] =
      StreamUnicastPublisher(stream)
  }

  private[interop] implicit class Runner[F[_]: Effect, A](fa: F[A])(implicit ioRuntime: IORuntime) {
    def reportFailure(e: Throwable) =
      Thread.getDefaultUncaughtExceptionHandler match {
        case null => e.printStackTrace()
        case h    => h.uncaughtException(Thread.currentThread(), e)
      }

    def unsafeRunAsync(): Unit =
      fa.to[IO].unsafeRunAsync {
        case Left(t)  => reportFailure(t)
        case Right(_) => ()
      }
  }
}
