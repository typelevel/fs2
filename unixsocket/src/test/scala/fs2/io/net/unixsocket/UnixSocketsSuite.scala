package fs2
package io.net.unixsocket

import scala.concurrent.duration._

import cats.effect.IO

class UnixSocketsSuite extends Fs2Suite {

  test("echoes") {
    val address = UnixSocketAddress("fs2-unix-sockets-test.sock")

    val server = UnixSockets[IO]
      .server(address)
      .map { client =>
        client.reads.through(client.writes)
      }
      .parJoinUnbounded

    def client(msg: Chunk[Byte]) = UnixSockets[IO].client(address).use { server =>
      server.write(msg) *> server.endOfOutput *> server.reads.compile
        .to(Chunk)
        .map(read => assertEquals(read, msg))
    }

    val clients = (0 until 100).map(b => client(Chunk.singleton(b.toByte)))

    (Stream.sleep_[IO](1.second) ++ Stream.emits(clients).evalMap(identity))
      .concurrently(server)
      .compile
      .drain
  }

}
