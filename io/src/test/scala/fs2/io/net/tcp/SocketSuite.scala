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
package io
package net
package tcp

import scala.concurrent.duration._

import cats.effect.IO

import com.comcast.ip4s._

class SocketSuite extends Fs2Suite {

  val timeout = 30.seconds

  val setup = for {
    serverSetup <- Network[IO].serverResource(address = Some(ip"127.0.0.1"))
    (bindAddress, server) = serverSetup
    clients = Stream
      .resource(Network[IO].client(bindAddress, options = List(SocketOption.sendBufferSize(10000))))
      .repeat
  } yield (server -> clients)

  group("tcp") {
    test("echo requests - each concurrent client gets back what it sent") {
      val message = Chunk.array("fs2.rocks".getBytes)
      val clientCount = 20L

      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val echoServer = server.map { socket =>
            socket
              .reads
              .through(socket.writes)
              .onFinalize(socket.endOfOutput)
          }.parJoinUnbounded

          val msgClients = clients
            .take(clientCount)
            .map { socket =>
              Stream
                .chunk(message)
                .through(socket.writes)
                .onFinalize(socket.endOfOutput) ++
                socket
                  .reads
                  .chunks
                  .map(bytes => new String(bytes.toArray))
            }
            .parJoin(10)
            .take(clientCount)

          msgClients.concurrently(echoServer)
        }
        .compile
        .toVector
        .map { it =>
          assertEquals(it.size.toLong, clientCount)
          assert(it.forall(_ == "fs2.rocks"))
        }
    }

    test("readN yields chunks of the requested size") {
      val message = Chunk.array("123456789012345678901234567890".getBytes)
      val sizes = Vector(1, 2, 3, 4, 3, 2, 1)

      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val junkServer = server.map { socket =>
            Stream
              .chunk(message)
              .through(socket.writes)
              .onFinalize(socket.endOfOutput)
          }.parJoinUnbounded

          val client =
            clients
              .take(1)
              .flatMap { socket =>
                Stream
                  .emits(sizes)
                  .evalMap(socket.readN(_))
                  .unNone
                  .map(_.size)
              }
              .take(sizes.length.toLong)

          client.concurrently(junkServer)
        }
        .compile
        .toVector
        .assertEquals(sizes)
    }

    test("write - concurrent calls do not cause a WritePendingException") {
      val message = Chunk.array(("123456789012345678901234567890" * 10000).getBytes)

      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val readOnlyServer = server.map(_.reads).parJoinUnbounded
          val client =
            clients.take(1).flatMap { socket =>
              // concurrent writes
              Stream {
                Stream.eval(socket.write(message)).repeatN(10L)
              }.repeatN(2L).parJoinUnbounded
            }

          client.concurrently(readOnlyServer)
        }
        .compile
        .drain
    }
  }
}
