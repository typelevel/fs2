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

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all._
import scala.concurrent.duration._

class BatcherSuite extends Fs2Suite {

  private type Slot = (Int, Deferred[IO, Either[Throwable, Int]])

  // Completes each slot with a result
  private def completeAll(requests: Chunk[Slot])(f: Int => Int): IO[Unit] =
    requests.traverse_ { case (a, result) => result.complete(Right(f(a))) }

  // Runs a batch after a delay, records the batch, and doubles each request
  private def recording(
      batches: Ref[IO, List[List[Int]]],
      delay: FiniteDuration = 10.millis
  ): Chunk[Slot] => IO[Unit] =
    requests =>
      batches.update(requests.map(_._1).toList :: _) *> IO.sleep(delay) *>
        completeAll(requests)(_ * 2)

  test("batches concurrent requests and gives each caller its own result") {
    val test = for {
      batches <- Ref[IO].of(List.empty[List[Int]])
      results <- Batcher[IO, Int, Int](maxBatchSize = 1000)(recording(batches)).use { double =>
        (1 to 100).toList.parTraverse(double)
      }
      recorded <- batches.get
    } yield {
      assertEquals(results, (1 to 100).toList.map(_ * 2))
      assertEquals(recorded.flatten.sorted, (1 to 100).toList)
      assert(recorded.exists(_.size > 1), s"no batch with more than 1 request: $recorded")
    }
    TestControl.executeEmbed(test)
  }

  test("respects maxBatchSize") {
    val test = for {
      batches <- Ref[IO].of(List.empty[List[Int]])
      results <- Batcher[IO, Int, Int](maxBatchSize = 7)(recording(batches)).use { double =>
        (1 to 100).toList.parTraverse(double)
      }
      recorded <- batches.get
    } yield {
      assertEquals(results, (1 to 100).toList.map(_ * 2))
      assertEquals(recorded.flatten.sorted, (1 to 100).toList)
      assert(recorded.forall(_.size <= 7), s"batch larger than 7: $recorded")
      assert(recorded.exists(_.size == 7), s"no full batch: $recorded")
    }
    TestControl.executeEmbed(test)
  }

  test("fails a request whose result runBatch leaves open") {
    // Completes only the even requests
    val runBatch: Chunk[Slot] => IO[Unit] =
      requests => IO.sleep(10.millis) *> completeAll(requests.filter(_._1 % 2 == 0))(_ * 2)
    val test = Batcher[IO, Int, Int](maxBatchSize = 100)(runBatch).use { double =>
      List(1, 2, 3, 4).parTraverse(double(_).attempt)
    }
    TestControl.executeEmbed(test).map { results =>
      assertEquals(results.collect { case Right(b) => b }, List(4, 8))
      List(results(0), results(2)).foreach {
        case Left(e: IllegalStateException) =>
          assertEquals(e.getMessage, "runBatch did not complete the result of a request")
        case other => fail(s"unexpected result: $other")
      }
    }
  }

  test("a failed runBatch fails the open requests and keeps the completed ones") {
    val failure = new RuntimeException("boom")
    // Completes only the even requests, then fails
    val runBatch: Chunk[Slot] => IO[Unit] =
      requests =>
        IO.sleep(10.millis) *> completeAll(requests.filter(_._1 % 2 == 0))(_ * 2) *>
          IO.raiseError(failure)
    val test = Batcher[IO, Int, Int](maxBatchSize = 100)(runBatch).use { double =>
      List(1, 2, 3, 4).parTraverse(double(_).attempt)
    }
    TestControl
      .executeEmbed(test)
      .assertEquals(List(Left(failure), Right(4), Left(failure), Right(8)))
  }

  test("fails waiting callers and later calls when released") {
    val test = for {
      started <- Deferred[IO, Unit]
      runBatch = (_: Chunk[Slot]) => started.complete(()) *> IO.never[Unit]
      allocated <- Batcher[IO, Int, Int](maxBatchSize = 1)(runBatch).allocated
      (double, release) = allocated
      // The first request is in progress, the second one waits in the queue
      inProgress <- double(1).attempt.start
      _ <- started.get
      queued <- double(2).attempt.start
      _ <- IO.sleep(1.second)
      _ <- release
      inProgressResult <- inProgress.joinWithNever
      queuedResult <- queued.joinWithNever
      laterResult <- double(3).attempt
    } yield List(inProgressResult, queuedResult, laterResult).foreach {
      case Left(e: IllegalStateException) =>
        assertEquals(e.getMessage, "Batcher has been released")
      case other => fail(s"unexpected result: $other")
    }
    TestControl.executeEmbed(test)
  }

  test("skips the request of a caller canceled while queued") {
    val test = for {
      batches <- Ref[IO].of(List.empty[List[Int]])
      gate <- Deferred[IO, Unit]
      runBatch = (requests: Chunk[Slot]) => {
        val as = requests.map(_._1).toList
        batches.update(as :: _) *> gate.get.whenA(as == List(1)) *>
          completeAll(requests)(identity)
      }
      results <- Batcher[IO, Int, Int](maxBatchSize = 100)(runBatch).use { identity =>
        for {
          first <- identity(1).start
          _ <- IO.sleep(1.second)
          canceled <- identity(2).start
          kept <- identity(3).start
          _ <- IO.sleep(1.second)
          _ <- canceled.cancel
          _ <- gate.complete(())
          firstResult <- first.joinWithNever
          keptResult <- kept.joinWithNever
          laterResult <- identity(4)
        } yield List(firstResult, keptResult, laterResult)
      }
      recorded <- batches.get
    } yield {
      assertEquals(results, List(1, 3, 4))
      assertEquals(recorded.reverse, List(List(1), List(3), List(4)))
    }
    TestControl.executeEmbed(test)
  }

  test("a caller canceled while its batch is in progress does not affect the batch") {
    val test = for {
      gate <- Deferred[IO, Unit]
      runBatch = (requests: Chunk[Slot]) => gate.get *> completeAll(requests)(identity)
      results <- Batcher[IO, Int, Int](maxBatchSize = 100)(runBatch).use { identity =>
        for {
          canceled <- identity(1).start
          kept <- identity(2).start
          _ <- IO.sleep(1.second)
          _ <- canceled.cancel
          _ <- gate.complete(())
          keptResult <- kept.joinWithNever
        } yield keptResult
      }
    } yield assertEquals(results, 2)
    TestControl.executeEmbed(test)
  }

  test("within waits up to maxWait for a batch to fill up") {
    val test = for {
      batches <- Ref[IO].of(List.empty[List[Int]])
      results <- Batcher
        .within[IO, Int, Int](maxBatchSize = 100, maxWait = 1.second)(
          recording(batches, delay = 0.millis)
        )
        .use { double =>
          (1 to 5).toList.parTraverse(i => IO.sleep(i.millis) *> double(i))
        }
      recorded <- batches.get
    } yield {
      assertEquals(results, List(2, 4, 6, 8, 10))
      assertEquals(recorded, List(List(1, 2, 3, 4, 5)))
    }
    TestControl.executeEmbed(test)
  }

  test("rejects a non-positive maxBatchSize") {
    intercept[IllegalArgumentException](
      Batcher[IO, Int, Int](maxBatchSize = 0)(_ => IO.unit)
    )
  }
}
