package fs2.io

import cats.effect.IO
import fs2.{Chunk, Fs2Spec, Stream}
import org.scalacheck.{Arbitrary, Gen}

class JavaInputOutputStreamSpec extends Fs2Spec {

  "ToInputStream" - {

    implicit val streamByteGen: Arbitrary[Stream[IO, Byte]] = Arbitrary {
      for {
        data <- implicitly[Arbitrary[String]].arbitrary
        chunkSize <- if (data.length > 0) Gen.chooseNum(1, data.length) else Gen.fail
      } yield {
        def go(rem: String): Stream[IO, Byte] = {
          if (chunkSize >= rem.length) Stream.chunk(Chunk.bytes(rem.getBytes))
          else {
            val (out, remainder) = rem.splitAt(chunkSize)
            Stream.chunk(Chunk.bytes(out.getBytes)) ++ go(remainder)
          }
        }
        go(data)
      }
    }

   "arbitrary.streams" in forAll { (stream: Stream[IO, Byte]) =>

      val example = stream.runLog.unsafeRunSync()

      val fromInputStream =
        stream.through(toInputStream).evalMap { is =>
          // consume in same thread pool. Production application should never do this,
          // instead they have to fork this to dedicated thread pool
          val buff = new Array[Byte](20)
          @annotation.tailrec
          def go(acc: Vector[Byte]): IO[Vector[Byte]] = {
            is.read(buff) match {
              case -1 => IO.pure(acc)
              case read => go(acc ++ buff.take(read))
            }
          }
          go(Vector.empty)
        }.runLog.map(_.flatten).unsafeRunSync()

      example shouldBe fromInputStream
    }

    "upstream.is.closed" in  {
      var closed: Boolean = false
      val s: Stream[IO, Byte] = Stream(1.toByte).onFinalize(IO { closed = true })

      s.through(toInputStream).run.unsafeRunSync()

      closed shouldBe true
    }

    "upstream.is.force-closed" in  {
      var closed: Boolean = false
      val s: Stream[IO, Byte] = Stream(1.toByte).onFinalize(IO { closed = true })

      val result =
        s.through(toInputStream).evalMap { is =>
          IO {
            is.close()
            closed // verifies that once close() terminates upstream was already cleaned up
          }
        }.runLog.unsafeRunSync()

      result shouldBe Vector(true)
    }

    "converts to 0..255 int values except EOF mark" in {
      val s: Stream[IO, Byte] = Stream.range(0, 256, 1).map(_.toByte)
      val result = s.through(toInputStream).map { is =>
        Vector.fill(257)(is.read())
      }.runLog.map(_.flatten).unsafeRunSync()
      result shouldBe (Stream.range(0, 256, 1) ++ Stream(-1)).toVector
    }

  }


}
