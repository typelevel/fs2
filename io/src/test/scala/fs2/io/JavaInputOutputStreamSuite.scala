package fs2.io

import cats.effect.IO
import fs2.{Chunk, Fs2Suite, Stream}
import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll

import scala.annotation.tailrec

class JavaInputOutputStreamSuite extends Fs2Suite {
  group("ToInputStream") {
    implicit val streamByteGenerator: Arbitrary[Stream[IO, Byte]] = Arbitrary {
      for {
        chunks <- pureStreamGenerator[Chunk[Byte]].arbitrary
      } yield chunks.flatMap(Stream.chunk).covary[IO]
    }

    property("arbitrary.streams") { forAll { (stream: Stream[IO, Byte]) =>
      val example = stream.compile.toVector.unsafeRunSync()

      val fromInputStream =
        toInputStreamResource(stream)
          .use { is =>
            // consume in same thread pool. Production application should never do this,
            // instead they have to fork this to dedicated thread pool
            val buff = new Array[Byte](20)
            @tailrec
            def go(acc: Vector[Byte]): IO[Vector[Byte]] =
              is.read(buff) match {
                case -1   => IO.pure(acc)
                case read => go(acc ++ buff.take(read))
              }
            go(Vector.empty)
          }
          .unsafeRunSync()

      assert(example == fromInputStream)
    }}

    test("upstream.is.closed".ignore) {
      // https://github.com/functional-streams-for-scala/fs2/issues/1063
      var closed: Boolean = false
      val s: Stream[IO, Byte] =
        Stream(1.toByte).onFinalize(IO { closed = true })

      toInputStreamResource(s).use(_ => IO.unit).unsafeRunSync()

      // eventually...
      assert(closed)
    }

    test("upstream.is.force-closed".ignore) {
      // https://github.com/functional-streams-for-scala/fs2/issues/1063
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

      assert(result)
    }

    test("converts to 0..255 int values except EOF mark") {
      Stream
        .range(0, 256, 1)
        .map(_.toByte)
        .covary[IO]
        .through(toInputStream)
        .map(is => Vector.fill(257)(is.read()))
        .compile
        .toVector
        .map(_.flatten)
        .map(it => assert(it == (Stream.range(0, 256, 1) ++ Stream(-1)).toVector))
    }
  }
}
