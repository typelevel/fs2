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

import cats.syntax.all._
import cats.effect.IO
import cats.effect.testkit.TestControl
import scala.concurrent.duration._

import org.scalacheck.effect.PropF.forAllF

class ChannelSuite extends Fs2Suite {

  test("receives some simple elements above capacity and closes") {
    val test = Channel.bounded[IO, Int](5).flatMap { chan =>
      val senders = 0.until(10).toList.parTraverse_ { i =>
        IO.sleep(i.millis) *> chan.send(i)
      }

      senders &> (IO.sleep(15.millis) *> chan.close *> chan.stream.compile.toVector)
    }

    TestControl.executeEmbed(test)
  }

  test("Channel receives all elements and closes") {
    forAllF { (source: Stream[Pure, Int]) =>
      Channel.unbounded[IO, Int].flatMap { chan =>
        chan.stream
          .concurrently {
            source
              .covary[IO]
              .rechunkRandomly()
              .through(chan.sendAll)
          }
          .compile
          .toVector
          .assertEquals(source.compile.toVector)
      }
    }
  }

  test("Slow consumer doesn't lose elements") {
    forAllF { (source: Stream[Pure, Int]) =>
      Channel.unbounded[IO, Int].flatMap { chan =>
        chan.stream
          .metered(1.millis)
          .concurrently {
            source
              .covary[IO]
              .rechunkRandomly()
              .through(chan.sendAll)
          }
          .compile
          .toVector
          .assertEquals(source.compile.toVector)
      }
    }
  }

  test("Blocked producers get dequeued") {
    forAllF { (source: Stream[Pure, Int]) =>
      Channel.synchronous[IO, Int].flatMap { chan =>
        chan.stream
          .metered(1.milli)
          .concurrently {
            source
              .covary[IO]
              .rechunkRandomly()
              .through(chan.sendAll)
          }
          .compile
          .toVector
          .assertEquals(source.compile.toVector)
      }
    }
  }

  test("Queued elements arrive in a single chunk") {
    val v = Vector(1, 2, 3)
    val p = for {
      chan <- Channel.unbounded[IO, Int]
      _ <- v.traverse(chan.send)
      _ <- chan.close
      res <- chan.stream.chunks.take(1).compile.lastOrError
    } yield res.toVector

    p.assertEquals(v)
  }

  test("trySend does not block") {
    val v = Vector(1, 2, 3, 4)
    val capacity = 3
    val p = for {
      chan <- Channel.bounded[IO, Int](capacity)
      _ <- v.traverse(chan.trySend)
      _ <- chan.close
      res <- chan.stream.chunks.take(1).compile.lastOrError
    } yield res.toVector

    p.assertEquals(v.take(capacity))
  }

  test("Timely closure") {
    val v = Vector(1, 2, 3)
    val p = for {
      chan <- Channel.unbounded[IO, Int]
      _ <- v.traverse(chan.send)
      _ <- chan.close
      _ <- v.traverse(chan.send)
      res <- chan.stream.compile.toVector
    } yield res

    p.assertEquals(v)
  }

  test("Closes before channel elements are depleted") {
    val p = for {
      chan <- Channel.unbounded[IO, Unit]
      _ <- chan.send(())
      _ <- chan.close
      isClosedBefore <- chan.isClosed
      _ <- chan.stream.compile.toVector
    } yield isClosedBefore

    p.assertEquals(true)
  }

  test("synchronous respects fifo") {
    val l = for {
      chan <- Channel.synchronous[IO, Int]
      _ <- (0 until 5).toList.traverse_ { i =>
        val f = for {
          _ <- IO.sleep(i.second)
          _ <- chan.send(i)
          _ <- if (i == 4) chan.close.void else IO.unit
        } yield ()
        f.start
      }
      result <- IO.sleep(5.seconds) *> chan.stream.compile.toList
    } yield result

    TestControl.executeEmbed(l).assertEquals((0 until 5).toList).parReplicateA_(100)
  }

  test("complete all blocked sends after closure") {
    val test = for {
      chan <- Channel.bounded[IO, Int](2)

      fiber <- 0.until(5).toList.parTraverse(chan.send(_)).start
      _ <- IO.sleep(1.second)
      _ <- chan.close

      results <- chan.stream.compile.toList
      _ <- IO(assert(results.length == 5))
      _ <- IO(assert(0.until(5).forall(results.contains(_))))

      sends <- fiber.joinWithNever
      _ <- IO(assert(sends.forall(_ == Right(()))))
    } yield ()

    TestControl.executeEmbed(test).parReplicateA_(100)
  }

  test("eagerly close sendAll upstream") {
    for {
      countR <- IO.ref(0)
      chan <- Channel.unbounded[IO, Unit]

      incrementer = Stream.eval(countR.update(_ + 1)).repeat.take(5)
      upstream = incrementer ++ Stream.eval(chan.close).drain ++ incrementer

      results <- chan.stream.concurrently(upstream.through(chan.sendAll)).compile.toList

      _ <- IO(assert(results.length == 5))
      count <- countR.get
      _ <- IO(assert(count == 6)) // we have to overrun the closure to detect it
    } yield ()
  }

  def blackHole(s: Stream[IO, Unit]) =
    s.repeatPull(_.uncons.flatMap {
      case None => Pull.pure(None)
      case Some((hd, tl)) =>
        val action = IO.delay(0.until(hd.size).foreach(_ => ()))
        Pull.eval(action).as(Some(tl))
    })

  @inline
  private def sendAll(list: List[Unit], action: IO[Unit]) =
    list.foldLeft(IO.unit)((acc, _) => acc *> action)

  test("sendPull") {
    val test = Channel.bounded[IO, Unit](8).flatMap { channel =>
      val action = List.fill(64)(()).traverse_(_ => channel.send(()).void) *> channel.close
      action.start *> channel.stream.through(blackHole).compile.drain
    }

    test.replicateA_(if (isJVM) 1000 else 1)
  }

  test("sendPullPar8") {
    val lists = List.fill(8)(List.fill(8)(()))

    val test = Channel.bounded[IO, Unit](8).flatMap { channel =>
      val action = lists.parTraverse_(sendAll(_, channel.send(()).void)) *> channel.close

      action &> channel.stream.through(blackHole).compile.drain
    }

    test.replicateA_(if (isJVM) 10000 else 1)
  }

  test("synchronous with many concurrents and close") {
    val test = Channel.synchronous[IO, Int].flatMap { ch =>
      0.until(20).toList.parTraverse_(i => ch.send(i).iterateWhile(_.isRight)) &>
        ch.stream.concurrently(Stream.eval(ch.close.delayBy(1.seconds))).compile.drain
    }

    test.parReplicateA(100)
  }

  test("complete closed immediately without draining") {
    val test = Channel.bounded[IO, Int](20).flatMap { ch =>
      for {
        _ <- 0.until(10).toList.parTraverse_(ch.send(_))
        _ <- ch.close
        _ <- ch.closed
      } yield ()
    }

    TestControl.executeEmbed(test)
  }

  def checkIfCloseWithElementClosing(channel: IO[Channel[IO, Int]]) =
    channel.flatMap { ch =>
      ch.closeWithElement(0) *> ch.isClosed.assert
    }

  test("closeWithElement closes right after sending the last element in bounded(1) case") {
    val channel = Channel.bounded[IO, Int](1)

    checkIfCloseWithElementClosing(channel)
  }

  test("closeWithElement closes right after sending the last element in bounded(2) case") {
    val channel = Channel.bounded[IO, Int](2)

    checkIfCloseWithElementClosing(channel)
  }

  test("closeWithElement closes right after sending the last element in unbounded case") {
    val channel = Channel.unbounded[IO, Int]

    checkIfCloseWithElementClosing(channel)
  }

  test("closeWithElement closes right after sending the last element in synchronous case") {
    val channel = Channel.synchronous[IO, Int]

    checkIfCloseWithElementClosing(channel)
  }

  def racingSendOperations(channel: IO[Channel[IO, Int]]) = {
    val expectedFirstCase = ((Right(()), Right(())), List(0, 1))
    val expectedSecondCase = ((Right(()), Left(Channel.Closed)), List(1))
    val expectedThirdCase = ((Left(Channel.Closed), Right(())), List(1))

    channel
      .flatMap { ch =>
        ch.send(0).both(ch.closeWithElement(1)).parProduct(ch.stream.compile.toList)
      }
      .map {
        case obtained @ ((Right(()), Right(())), List(0, 1)) =>
          assertEquals(obtained, expectedFirstCase)
        case obtained @ ((Right(()), Left(Channel.Closed)), List(1)) =>
          assertEquals(obtained, expectedSecondCase)
        case obtained @ ((Left(Channel.Closed), Right(())), List(1)) =>
          assertEquals(obtained, expectedThirdCase)
        case e => fail(s"An unknown test result: $e")
      }
      .replicateA_(if (isJVM) 1000 else 1)
  }

  test("racing send and closeWithElement should work in bounded(1) case") {
    val channel = Channel.bounded[IO, Int](1)

    racingSendOperations(channel)
  }

  test("racing send and closeWithElement should work in bounded(2) case") {
    val channel = Channel.bounded[IO, Int](2)

    racingSendOperations(channel)
  }

  test("racing send and closeWithElement should work in unbounded case") {
    val channel = Channel.unbounded[IO, Int]

    racingSendOperations(channel)
  }

  test("racing send and closeWithElement should work in synchronous case") {
    val channel = Channel.synchronous[IO, Int]

    racingSendOperations(channel)
  }

}
