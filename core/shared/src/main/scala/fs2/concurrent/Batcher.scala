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
  * There are three variants, which differ in how `runBatch` gives the
  * results back to the callers:
  *
  *  - [[Batcher.apply]]: `runBatch` gets each request together with a
  *    `Deferred` and completes the result of each request itself.
  *  - [[Batcher.keyed]]: `runBatch` gets the requests as a `Map` by key and
  *    returns the results as a `Map` by key. Requests with equal keys in the
  *    same batch are sent only once.
  *  - [[Batcher.positional]]: `runBatch` gets the requests as a `Chunk` and
  *    returns the results as a `Chunk`, matched to the requests by position.
  *
  * Each variant has a counterpart that waits up to a maximum time for a
  * batch to fill up: [[Batcher.within]], [[Batcher.keyedWithin]] and
  * [[Batcher.positionalWithin]].
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
  * scala> Batcher.keyed[IO, Int, Int, String](maxBatchSize = 100)(identity) { ids =>
  *      |   IO.pure(ids.map { case (id, _) => id -> s"user-$id" })
  *      | }.use(lookup => List(1, 2, 1).parTraverse(lookup)).unsafeRunSync()
  * res0: List[String] = List(user-1, user-2, user-1)
  * }}}
  */
object Batcher {

  /** Creates a batcher that runs batches of up to `maxBatchSize` requests,
    * where `runBatch` completes the result of each request itself.
    *
    * `runBatch` gets each request of the batch together with a `Deferred`
    * for its result, and must complete each `Deferred` with the result
    * (`Right`) or the error (`Left`) of its request. Because each result is
    * put directly into the slot of its own request, the results cannot get
    * mixed up.
    *
    * If `runBatch` fails, all the requests of the batch whose results are
    * not yet complete fail with the same error. If `runBatch` completes
    * without completing the result of a request, that request fails with an
    * `IllegalStateException`, so that no caller waits forever. A result that
    * is already complete cannot be changed: `Deferred.complete` then returns
    * `false`.
    *
    * Each batch contains all the requests that wait when the previous batch
    * completes, up to `maxBatchSize`. A request that comes in when no batch
    * is in progress starts a new batch immediately.
    *
    * @param maxBatchSize the maximum number of requests in one batch, must be > 0
    * @param runBatch runs one batch of requests and completes the result of
    *                 each request
    */
  def apply[F[_], A, B](maxBatchSize: Int)(
      runBatch: Chunk[(A, Deferred[F, Either[Throwable, B]])] => F[Unit]
  )(implicit F: Concurrent[F]): Resource[F, A => F[B]] = {
    requirePositive(maxBatchSize)
    create[F, A, B](_.chunkLimit(maxBatchSize))(runBatch)
  }

  /** Like [[Batcher.apply]], but waits up to `maxWait` for a batch to fill
    * up.
    *
    * A batch starts when it contains `maxBatchSize` requests, or when
    * `maxWait` has elapsed since its first request came in, whichever
    * happens first. This gives larger batches under low load, at the cost of
    * extra latency. See [[Stream.groupWithin]].
    *
    * @param maxBatchSize the maximum number of requests in one batch, must be > 0
    * @param maxWait the maximum time that a request waits for its batch to fill up
    * @param runBatch runs one batch of requests and completes the result of
    *                 each request
    */
  def within[F[_], A, B](maxBatchSize: Int, maxWait: FiniteDuration)(
      runBatch: Chunk[(A, Deferred[F, Either[Throwable, B]])] => F[Unit]
  )(implicit F: Temporal[F]): Resource[F, A => F[B]] = {
    requirePositive(maxBatchSize)
    create[F, A, B](_.groupWithin(maxBatchSize, maxWait))(runBatch)
  }

  /** Creates a batcher that runs batches of up to `maxBatchSize` requests,
    * where `runBatch` gets the requests and returns the results by key.
    *
    * The requests of a batch are grouped by `key`. `runBatch` gets one
    * request per key, and returns a `Map` from each key to its result. The
    * result of each key is given to all the callers whose request has that
    * key. The order of the entries in the returned `Map` does not matter.
    *
    * Requests with equal keys must be interchangeable: only the first
    * request of each key in a batch is given to `runBatch`, the others are
    * dropped. This deduplication applies to one batch only, the results are
    * not cached between batches.
    *
    * If the returned `Map` has no result for a key, all the requests with
    * that key fail with a `NoSuchElementException`. Extra keys are ignored.
    * If `runBatch` fails, all the requests of the batch fail with the same
    * error.
    *
    * @param maxBatchSize the maximum number of requests in one batch, must be > 0
    * @param key gives the key of a request
    * @param runBatch runs one batch of requests and returns the results by key
    */
  def keyed[F[_], K, A, B](maxBatchSize: Int)(key: A => K)(
      runBatch: Map[K, A] => F[Map[K, B]]
  )(implicit F: Concurrent[F]): Resource[F, A => F[B]] =
    apply[F, A, B](maxBatchSize)(byKey(key)(runBatch))

  /** Like [[Batcher.keyed]], but waits up to `maxWait` for a batch to fill
    * up. See [[Batcher.within]].
    *
    * @param maxBatchSize the maximum number of requests in one batch, must be > 0
    * @param maxWait the maximum time that a request waits for its batch to fill up
    * @param key gives the key of a request
    * @param runBatch runs one batch of requests and returns the results by key
    */
  def keyedWithin[F[_], K, A, B](maxBatchSize: Int, maxWait: FiniteDuration)(key: A => K)(
      runBatch: Map[K, A] => F[Map[K, B]]
  )(implicit F: Temporal[F]): Resource[F, A => F[B]] =
    within[F, A, B](maxBatchSize, maxWait)(byKey(key)(runBatch))

  private def byKey[F[_], K, A, B](key: A => K)(runBatch: Map[K, A] => F[Map[K, B]])(implicit
      F: Concurrent[F]
  ): Chunk[(A, Deferred[F, Either[Throwable, B]])] => F[Unit] =
    requests => {
      val groups = requests.toList.groupBy { case (a, _) => key(a) }
      runBatch(groups.map { case (k, group) => k -> group.head._1 }).flatMap { results =>
        groups.toList.traverse_ { case (k, group) =>
          val result = results.get(k).toRight[Throwable](missingKey(k))
          group.traverse_ { case (_, slot) => slot.complete(result) }
        }
      }
    }

  private def missingKey(key: Any): Throwable =
    new NoSuchElementException(s"runBatch returned no result for key ${String.valueOf(key)}")

  private final class Request[F[_], A, B](
      val value: A,
      val result: Deferred[F, Either[Throwable, B]]
  )

  // Completes the result of a request whose caller was canceled,
  // nobody reads it
  private val CallerCanceled: Throwable = new RuntimeException("Caller canceled")

  private def released: Throwable = new IllegalStateException("Batcher has been released")

  private def notCompleted: Throwable =
    new IllegalStateException("runBatch did not complete the result of a request")

  private def requirePositive(maxBatchSize: Int): Unit =
    require(maxBatchSize > 0, s"maxBatchSize must be > 0, but got ${maxBatchSize.toString}")

  private def create[F[_], A, B](
      batches: Pipe[F, Request[F, A, B], Chunk[Request[F, A, B]]]
  )(
      runBatch: Chunk[(A, Deferred[F, Either[Throwable, B]])] => F[Unit]
  )(implicit F: Concurrent[F]): Resource[F, A => F[B]] = {

    def process(all: Chunk[Request[F, A, B]]): F[Unit] =
      // Skip the requests whose callers were canceled
      all.filterA(_.result.tryGet.map(_.isEmpty)).flatMap { batch =>
        if (batch.isEmpty) F.unit
        else
          (F.unit >> runBatch(batch.map(request => (request.value, request.result)))).attempt
            .flatMap { outcome =>
              // Fail the requests that runBatch left open, so that no caller waits forever
              val error = outcome.fold(identity, _ => notCompleted)
              batch.traverse_(_.result.complete(Left(error)))
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
