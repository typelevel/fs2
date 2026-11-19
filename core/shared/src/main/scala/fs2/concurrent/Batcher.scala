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
package concurrent

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

/** Batches requests submitted by many concurrent callers.
  *
  * A batcher is a function `A => F[B]` that can be called concurrently.
  * Each call submits one request and semantically blocks until the result
  * of that request is available. A single background fiber takes the
  * waiting requests in batches and runs each batch with one call to
  * `runBatch`. The batches grow when requests come in faster than they are
  * processed. This is useful, for example, to commit many writes in one
  * transaction (group commit), or to answer many lookups with one query.
  *
  * `runBatch` must return exactly one result per request, in the same order
  * as the requests. If `runBatch` fails, or returns a different number of
  * results, the requests of that batch are run again one at a time, so that
  * each caller gets the result or the error of its own request.
  *
  * The background fiber is tied to the returned resource. When the resource
  * is released, the batch in progress is canceled, and all the callers that
  * still wait for a result, and all later calls, fail with an
  * `IllegalStateException`.
  *
  * If a caller is canceled while its request still waits in the queue, the
  * request is skipped. If the request is already part of a batch in
  * progress, the batch is not affected.
  *
  * @example {{{
  * scala> import cats.effect.IO, cats.effect.unsafe.implicits.global, cats.syntax.all._
  * scala> Batcher[IO, String, Int](maxBatchSize = 100) { names =>
  *      |   IO.pure(names.map(_.length))
  *      | }.use(lookup => List("Alice", "Bob").parTraverse(lookup)).unsafeRunSync()
  * res0: List[Int] = List(5, 3)
  * }}}
  */
object Batcher {

  /** Creates a batcher that runs batches of up to `maxBatchSize` requests.
    *
    * Each batch contains all the requests that wait when the previous batch
    * completes, up to `maxBatchSize`. A request that comes in when no batch
    * is in progress starts a new batch immediately.
    *
    * @param maxBatchSize the maximum number of requests in one batch, must be > 0
    * @param runBatch runs one batch of requests and returns one result per
    *                 request, in the same order
    */
  def apply[F[_], A, B](maxBatchSize: Int)(runBatch: Chunk[A] => F[Chunk[B]])(implicit
      F: Concurrent[F]
  ): Resource[F, A => F[B]] = {
    require(maxBatchSize > 0, s"maxBatchSize must be > 0, but got ${maxBatchSize.toString}")
    create[F, A, B](_.chunkLimit(maxBatchSize))(runBatch)
  }

  /** Creates a batcher that runs batches of up to `maxBatchSize` requests,
    * and waits up to `maxWait` for a batch to fill up.
    *
    * A batch starts when it contains `maxBatchSize` requests, or when
    * `maxWait` has elapsed since its first request came in, whichever
    * happens first. This gives larger batches under low load, at the cost of
    * extra latency. See [[Stream.groupWithin]].
    *
    * @param maxBatchSize the maximum number of requests in one batch, must be > 0
    * @param maxWait the maximum time that a request waits for its batch to fill up
    * @param runBatch runs one batch of requests and returns one result per
    *                 request, in the same order
    */
  def within[F[_], A, B](maxBatchSize: Int, maxWait: FiniteDuration)(
      runBatch: Chunk[A] => F[Chunk[B]]
  )(implicit F: Temporal[F]): Resource[F, A => F[B]] = {
    require(maxBatchSize > 0, s"maxBatchSize must be > 0, but got ${maxBatchSize.toString}")
    create[F, A, B](_.groupWithin(maxBatchSize, maxWait))(runBatch)
  }

  private final class Request[F[_], A, B](
      val value: A,
      val result: Deferred[F, Either[Throwable, B]]
  )

  // Completes the result of a request whose caller was canceled,
  // nobody reads it
  private val CallerCanceled: Throwable = new RuntimeException("Caller canceled")

  private def released: Throwable = new IllegalStateException("Batcher has been released")

  private def create[F[_], A, B](
      batches: Stream[F, Request[F, A, B]] => Stream[F, Chunk[Request[F, A, B]]]
  )(runBatch: Chunk[A] => F[Chunk[B]])(implicit F: Concurrent[F]): Resource[F, A => F[B]] = {

    def runOne(a: A): F[B] =
      (F.unit >> runBatch(Chunk.singleton(a))).flatMap { results =>
        if (results.size == 1) F.pure(results(0))
        else F.raiseError(wrongSize(1, results.size))
      }

    def wrongSize(expected: Int, actual: Int): Throwable =
      new IllegalStateException(
        s"runBatch returned ${actual.toString} results for ${expected.toString} requests"
      )

    def process(all: Chunk[Request[F, A, B]]): F[Unit] =
      // Skip the requests whose callers were canceled
      all.filterA(_.result.tryGet.map(_.isEmpty)).flatMap { batch =>
        if (batch.isEmpty) F.unit
        else
          (F.unit >> runBatch(batch.map(_.value))).attempt.flatMap {
            case Right(results) if results.size == batch.size =>
              batch.zip(results).traverse_ { case (request, b) =>
                request.result.complete(Right(b))
              }
            case Right(results) if batch.size == 1 =>
              batch.traverse_(_.result.complete(Left(wrongSize(1, results.size))))
            case Left(error) if batch.size == 1 =>
              batch.traverse_(_.result.complete(Left(error)))
            case _ =>
              // Isolate the failure: run the requests one at a time
              batch.traverse_ { request =>
                runOne(request.value).attempt.flatMap(request.result.complete)
              }
          }
      }

    for {
      channel <- Resource.eval(Channel.unbounded[F, Request[F, A, B]])
      // Completed after the background fiber has stopped,
      // so that no result can be completed later
      stopped <- Resource.make(Deferred[F, Unit])(stopped =>
        channel.close *> stopped.complete(()).void
      )
      _ <- batches(channel.stream).foreach(process).compile.drain.background
    } yield (a: A) =>
      Deferred[F, Either[Throwable, B]].flatMap { result =>
        channel
          .send(new Request(a, result))
          .flatMap {
            case Left(Channel.Closed) => F.raiseError[B](released)
            case Right(())            =>
              F.race(result.get, stopped.get).flatMap {
                case Left(b)   => b.liftTo[F]
                case Right(()) =>
                  result.tryGet.flatMap {
                    case Some(b) => b.liftTo[F]
                    case None    => F.raiseError[B](released)
                  }
              }
          }
          .onCancel(result.complete(Left(CallerCanceled)).void)
      }
  }
}
