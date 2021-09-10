/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package interop

import cats.effect.kernel._
import cats.effect.std.Dispatcher
import org.reactivestreams._

/** Implementation of the reactivestreams protocol for fs2
  *
  * @example
  *   {{{ scala> import fs2._ scala> import fs2.interop.reactivestreams._ scala> import
  *   cats.effect.{IO, Resource}, cats.effect.unsafe.implicits.global scala> scala> val upstream:
  *   Stream[IO, Int] = Stream(1, 2, 3).covary[IO] scala> val publisher: Resource[IO,
  *   StreamUnicastPublisher[IO, Int]] = upstream.toUnicastPublisher scala> val downstream:
  *   Stream[IO, Int] = Stream.resource(publisher).flatMap(_.toStream[IO]) scala> scala>
  *   downstream.compile.toVector.unsafeRunSync() res0: Vector[Int] = Vector(1, 2, 3) }}}
  *
  * @see
  *   [[http://www.reactive-streams.org/]]
  */
package object reactivestreams {

  /** Creates a lazy stream from an `org.reactivestreams.Publisher`.
    *
    * The publisher only receives a subscriber when the stream is run.
    */
  def fromPublisher[F[_]: Async, A](p: Publisher[A]): Stream[F, A] =
    Stream
      .resource(Dispatcher[F])
      .flatMap { dispatcher =>
        Stream
          .eval(StreamSubscriber[F, A](dispatcher))
          .flatMap(s => s.sub.stream(Sync[F].delay(p.subscribe(s))))
      }

  implicit final class PublisherOps[A](val publisher: Publisher[A]) extends AnyVal {

    /** Creates a lazy stream from an `org.reactivestreams.Publisher` */
    def toStream[F[_]: Async]: Stream[F, A] =
      fromPublisher(publisher)
  }

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) {

    /** Creates a [[StreamUnicastPublisher]] from a stream.
      *
      * This publisher can only have a single subscription. The stream is only ran when elements are
      * requested.
      */
    def toUnicastPublisher(implicit
        F: Async[F]
    ): Resource[F, StreamUnicastPublisher[F, A]] =
      Dispatcher[F].map { dispatcher =>
        StreamUnicastPublisher(stream, dispatcher)
      }
  }
}
