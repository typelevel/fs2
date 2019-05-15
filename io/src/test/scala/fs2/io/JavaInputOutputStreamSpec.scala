package fs2.io

import cats.effect.IO
import fs2.{Chunk, EventuallySupport, Fs2Spec, Stream}
import org.scalatest.prop.Generator

class JavaInputOutputStreamSpec extends Fs2Spec with EventuallySupport {

  "ToInputStream" - {

    implicit val streamByteGenerator: Generator[Stream[IO, Byte]] =
      for {
        data <- strings
        chunkSize <- intsBetween(1.min(data.length), data.length)
      } yield {
        def go(rem: String): Stream[IO, Byte] =
          if (chunkSize >= rem.length) Stream.chunk(Chunk.bytes(rem.getBytes))
          else {
            val (out, remainder) = rem.splitAt(chunkSize)
            Stream.chunk(Chunk.bytes(out.getBytes)) ++ go(remainder)
          }
        go(data)
      }

    "arbitrary.streams" in forAll { (stream: Stream[IO, Byte]) =>
      val example = stream.compile.toVector.unsafeRunSync()

      val fromInputStream =
        toInputStreamResource(stream)
          .use { is =>
            // consume in same thread pool. Production application should never do this,
            // instead they have to fork this to dedicated thread pool
            val buff = new Array[Byte](20)
            @annotation.tailrec
            def go(acc: Vector[Byte]): IO[Vector[Byte]] =
              is.read(buff) match {
                case -1   => IO.pure(acc)
                case read => go(acc ++ buff.take(read))
              }
            go(Vector.empty)
          }
          .unsafeRunSync()

      example shouldBe fromInputStream
    }

    "upstream.is.closed" in {
      pending // https://github.com/functional-streams-for-scala/fs2/issues/1063
      var closed: Boolean = false
      val s: Stream[IO, Byte] =
        Stream(1.toByte).onFinalize(IO { closed = true })

      toInputStreamResource(s).use(_ => IO.unit).unsafeRunSync()

      eventually { closed shouldBe true }
    }

    "upstream.is.force-closed" in {
      pending // https://github.com/functional-streams-for-scala/fs2/issues/1063
      var closed: Boolean = false
      val s: Stream[IO, Byte] =
        Stream(1.toByte).onFinalize(IO { closed = true })

      val result =
        toInputStreamResource(s)
          .use { is =>
            IO {
              is.close()
              closed // verifies that once close() terminates upstream was already cleaned up
            }
          }
          .unsafeRunSync()

      result shouldBe Vector(true)
    }

    "converts to 0..255 int values except EOF mark" in {
      Stream
        .range(0, 256, 1)
        .map(_.toByte)
        .covary[IO]
        .through(toInputStream)
        .map { is =>
          Vector.fill(257)(is.read())
        }
        .compile
        .toVector
        .map(_.flatten)
        .asserting(_ shouldBe (Stream.range(0, 256, 1) ++ Stream(-1)).toVector)
    }
  }
}
