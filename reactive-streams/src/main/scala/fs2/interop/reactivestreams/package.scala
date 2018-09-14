package fs2
package interop

import cats.effect._
import cats.effect.implicits._
import org.reactivestreams._

package object reactivestreams {

  /**
    * Creates a lazy stream from an org.reactivestreams.Publisher.
    *
    * The publisher only receives a subscriber when the stream is run.
    */
  def fromPublisher[F[_]: ConcurrentEffect, A](p: Publisher[A]): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A])
      .evalTap { s =>
        Sync[F].delay(p.subscribe(s))
      }
      .flatMap(_.sub.stream)

  implicit final class PublisherOps[A](val pub: Publisher[A]) extends AnyVal {

    /** Creates a lazy stream from an org.reactivestreams.Publisher */
    def toStream[F[_]: ConcurrentEffect](): Stream[F, A] =
      fromPublisher(pub)
  }

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) {

    /**
      * Creates a [[fs2.interop.reactivestreams.StreamUnicastPublisher]] from a stream.
      *
      * This publisher can only have a single subscription.
      * The stream is only ran when elements are requested.
      */
    def toUnicastPublisher()(implicit F: ConcurrentEffect[F]): StreamUnicastPublisher[F, A] =
      StreamUnicastPublisher(stream)
  }

  private[interop] implicit class Runner[F[_]: ConcurrentEffect, A](fa: F[A]) {
    def reportFailure(e: Throwable) =
      Thread.getDefaultUncaughtExceptionHandler match {
        case null => e.printStackTrace()
        case h    => h.uncaughtException(Thread.currentThread(), e)
      }

    def unsafeRunAsync(): Unit =
      fa.runAsync {
        case Left(e)  => IO(reportFailure(e))
        case Right(_) => IO.unit
      }.unsafeRunSync
  }
}
