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
package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}
import cats.syntax.all._
import fs2.concurrent.Channel
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
class ChannelBenchmark {
  @Param(Array("64", "1024", "16384"))
  var size: Int = _

  var list: List[Unit] = _
  var lists: List[List[Unit]] = _

  @Setup
  def setup() = {
    list = List.fill(size)(())
    val subList = List.fill(size / 8)(())
    lists = List.fill(8)(subList)
  }

  @Benchmark
  def sendPull(): Unit =
    Channel
      .bounded[IO, Unit](size / 8)
      .flatMap { channel =>
        val action = sendAll(list, channel.send(()).void) *> channel.close
        action.start *> channel.stream.through(blackHole).compile.drain
      }
      .unsafeRunSync()

  @Benchmark
  def sendPullPar8(): Unit =
    Channel
      .bounded[IO, Unit](size / 8)
      .flatMap { channel =>
        val action = lists.parTraverse_(sendAll(_, channel.send(()).void)) *> channel.close
        action.start *> channel.stream.through(blackHole).compile.drain
      }
      .unsafeRunSync()

  @Benchmark
  def sendPullParUnlimited(): Unit =
    Channel
      .bounded[IO, Unit](size / 8)
      .flatMap { channel =>
        val action = sendAll(list, channel.send(()).start.void)
        action *> channel.stream.take(size.toLong).through(blackHole).compile.drain
      }
      .unsafeRunSync()

  @inline
  private def sendAll(list: List[Unit], action: IO[Unit]) =
    list.foldLeft(IO.unit)((acc, _) => acc *> action)

  private def blackHole(s: Stream[IO, Unit]) =
    s.repeatPull(_.uncons.flatMap {
      case None => Pull.pure(None)
      case Some((hd, tl)) =>
        val action = IO.delay(0.until(hd.size).foreach(_ => Blackhole.consumeCPU(100)))
        Pull.eval(action).as(Some(tl))
    })
}
