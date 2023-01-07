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

import cats.effect.kernel.{Async, Resource}
import java.util.concurrent.Flow.Publisher

/** Implementation of the reactive-streams protocol for fs2; based on Java Flow.
  *
  * @example {{{
  * scala> import fs2.Stream
  * scala> import fs2.interop.flow._
  * scala> import java.util.concurrent.Flow.Publisher
  * scala> import cats.effect.{IO, Resource}
  * scala> import cats.effect.unsafe.implicits.global
  * scala>
  * scala> val upstream: Stream[IO, Int] = Stream(1, 2, 3).covary[IO]
  * scala> val publisher: Resource[IO, Publisher[Int]] = upstream.toPublisher
  * scala> val downstream: Stream[IO, Int] = Stream.resource(publisher).flatMap(p => p.toStream[IO](bufferSize = 16))
  * scala>
  * scala> downstream.compile.toVector.unsafeRunSync()
  * res0: Vector[Int] = Vector(1, 2, 3)
  * }}}
  *
  * @see [[http://www.reactive-streams.org/]]
  */
package object flow {

  /** Creates a [[Stream]] from an [[Publisher]].
    *
    * The publisher only receives a subscriber when the stream is run.
    *
    * @param publisher The [[Publisher]] to transform.
    * @param bufferSize setup the number of elements asked each time from the [[Publisher]].
    *                   A high number can be useful if the publisher is triggering from IO,
    *                   like requesting elements from a database.
    *                   The publisher can use this `bufferSize` to query elements in batch.
    *                   A high number will also lead to more elements in memory.
    */
  def fromPublisher[F[_], A](
      publisher: Publisher[A],
      bufferSize: Int
  )(implicit
      F: Async[F]
  ): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A](bufferSize))
      .flatMap { subscriber =>
        subscriber.stream(
          F.delay(publisher.subscribe(subscriber))
        )
      }

  implicit final class PublisherOps[A](private val publisher: Publisher[A]) extends AnyVal {

    /** Creates a [[Stream]] from an [[Publisher]].
      *
      * @param bufferSize setup the number of elements asked each time from the [[Publisher]].
      *                   A high number can be useful is the publisher is triggering from IO,
      *                   like requesting elements from a database.
      *                   The publisher can use this `bufferSize` to query elements in batch.
      *                   A high number will also lead to more elements in memory.
      */
    def toStream[F[_]](bufferSize: Int)(implicit F: Async[F]): Stream[F, A] =
      flow.fromPublisher(publisher, bufferSize)
  }

  /** Creates a [[Publisher]] from a [[Stream]].
    *
    * This publisher can only have a single subscription.
    * The stream is only ran when elements are requested.
    *
    * @param stream The [[Stream]] to transform.
    */
  def toPublisher[F[_], A](
      stream: Stream[F, A]
  )(implicit
      F: Async[F]
  ): Resource[F, Publisher[A]] =
    StreamUnicastPublisher(stream)

  implicit final class StreamOps[F[_], A](private val stream: Stream[F, A]) extends AnyVal {

    /** Creates a [[Publisher]] from a [[Stream]].
      *
      * This publisher can only have a single subscription.
      * The stream is only ran when elements are requested.
      */
    def toPublisher(implicit F: Async[F]): Resource[F, Publisher[A]] =
      flow.toPublisher(stream)
  }

  /** Ensures a value is not null. */
  private[flow] def nonNull[A](a: A): Unit =
    if (a == null) {
      throw new NullPointerException()
    }
}
