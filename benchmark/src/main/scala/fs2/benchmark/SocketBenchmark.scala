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
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.net.{Network, Socket}
import org.openjdk.jmh.annotations.{Benchmark, Level, Param, Scope, Setup, State, TearDown}

/** Socket reads over loopback TCP, with fs2 on both ends. */
class SocketBenchmark {
  import SocketBenchmark._

  /** Each connection does `Rounds` round trips of a `MessageSize` message in parallel. */
  @Benchmark
  def pingPong(state: PingPongState): Unit =
    state.clients
      .parTraverse_ { client =>
        (client.write(state.message) *> readBytes(client, MessageSize)).replicateA_(Rounds)
      }
      .unsafeRunSync()

  /** Requests `BulkSize` bytes from the server and reads them. */
  @Benchmark
  def bulk(state: BulkState): Unit =
    (state.client.write(Chunk.singleton(0.toByte)) *> readBytes(state.client, BulkSize))
      .unsafeRunSync()
}

object SocketBenchmark {
  final val MessageSize = 100
  final val Rounds = 100
  final val BulkSize = 8 * 1024 * 1024
  final val ReadSize = 8192

  private val address = SocketAddress(ip"127.0.0.1", Port.Wildcard)

  /** Reads exactly `n` bytes using `read`. */
  def readBytes(socket: Socket[IO], n: Int): IO[Unit] =
    socket.read(ReadSize).flatMap {
      case Some(bytes) if bytes.size < n => readBytes(socket, n - bytes.size)
      case Some(_)                       => IO.unit
      case None                          => IO.raiseError(new RuntimeException("unexpected EOF"))
    }

  @State(Scope.Benchmark)
  class PingPongState {
    @Param(Array("1", "16"))
    var connections: Int = _

    val message: Chunk[Byte] = Chunk.array(new Array[Byte](MessageSize))
    var clients: List[Socket[IO]] = _
    private var release: IO[Unit] = _

    @Setup(Level.Trial)
    def setup(): Unit = {
      val echo = for {
        server <- Network[IO].bind(address)
        _ <- server.accept
          .take(connections.toLong)
          .map(socket => socket.reads.through(socket.writes))
          .parJoinUnbounded
          .compile
          .drain
          .background
        clients <- List.fill(connections)(Network[IO].connect(server.address)).sequence
      } yield clients
      val (c, r) = echo.allocated.unsafeRunSync()
      clients = c
      release = r
    }

    @TearDown(Level.Trial)
    def tearDown(): Unit = release.unsafeRunSync()
  }

  @State(Scope.Benchmark)
  class BulkState {
    var client: Socket[IO] = _
    private var release: IO[Unit] = _

    @Setup(Level.Trial)
    def setup(): Unit = {
      val data = Chunk.array(new Array[Byte](BulkSize))
      val bulk = for {
        server <- Network[IO].bind(address)
        _ <- server.accept
          .take(1)
          .map { socket =>
            Stream
              .repeatEval(socket.read(1))
              .unNoneTerminate
              .foreach(_ => socket.write(data))
          }
          .parJoinUnbounded
          .compile
          .drain
          .background
        client <- Network[IO].connect(server.address)
      } yield client
      val (c, r) = bulk.allocated.unsafeRunSync()
      client = c
      release = r
    }

    @TearDown(Level.Trial)
    def tearDown(): Unit = release.unsafeRunSync()
  }
}
