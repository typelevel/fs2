package fs2
package io
package tcp

import scala.concurrent.duration.FiniteDuration

import java.net.{SocketAddress, InetSocketAddress}

import cats.effect.{Blocker, IO}
import cats.implicits._

import fs2.io.tls.TLSContext
import cats.effect.Sync
import scodec.bits.ByteVector

class TLSSocketSpec extends Fs2Spec {
  "TLSSocket" - {
    "google" in {
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          socketGroup.client[IO](new InetSocketAddress("google.com", 443)).use { socket =>
            TLSContext.insecure[IO](blocker).engine(enabledProtocols = Some(List("TLSv1.3"))).flatMap { tlsEngine =>
              TLSSocket(loggingSocket("raw", socket), tlsEngine).flatMap { tlsSocket =>
                (Stream("GET /\r\n\r\n")
                  .covary[IO]
                  .through(text.utf8Encode)
                  .through(tlsSocket.writes())
                  .drain ++
                  tlsSocket.reads(8192).through(text.utf8Decode))
                  .through(text.lines)
                  .head
                  .compile
                  .string
                  .asserting(_ shouldBe "HTTP/1.0 200 OK")
              }
            }
          }
        }
      }
    }
  }

  def loggingSocket[F[_]: Sync](tag: String, socket: Socket[F]): Socket[F] = new Socket[F] {
      private def log(msg: String): F[Unit] = Sync[F].delay(println(s"$tag: $msg"))

      override def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        log(s"readN $numBytes") *> socket.readN(numBytes, timeout).flatTap(res => log(s"readN result: $res"))

      override def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = 
        log(s"read $maxBytes") *> socket.read(maxBytes, timeout).flatTap(res => log(s"read result: ${res.map(c => ByteVector.view(c.toArray))}"))

      def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        log(s"write ${ByteVector.view(bytes.toArray)}") *> socket.write(bytes, timeout)

      def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] =
        Stream.repeatEval(read(maxBytes, timeout)).unNoneTerminate.flatMap(Stream.chunk)

      def writes(timeout: Option[FiniteDuration]): Pipe[F, Byte, Unit] =
        _.chunks.evalMap(write(_, timeout))

      def endOfOutput: F[Unit] = socket.endOfOutput

      def endOfInput: F[Unit] = socket.endOfInput

      def localAddress: F[SocketAddress] = socket.localAddress

      def remoteAddress: F[SocketAddress] = socket.remoteAddress

      def close: F[Unit] = socket.close

      def isOpen: F[Boolean] = socket.isOpen
 
  }
}
