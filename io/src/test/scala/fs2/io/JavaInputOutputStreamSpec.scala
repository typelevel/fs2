package fs2.io

import fs2.{Chunk, Fs2Spec, Stream, Task}
import org.scalacheck.{Arbitrary, Gen}

import scala.annotation.tailrec



class JavaInputOutputStreamSpec extends Fs2Spec {

  "ToInputStream" - {

    implicit val streamByteGen: Arbitrary[Stream[Task, Byte]] = Arbitrary {
      for {
        data <- implicitly[Arbitrary[String]].arbitrary
        chunkSize <- Gen.chooseNum(1, data.length)
      } yield {
        def go(rem: String): Stream[Task, Byte] = {
          if (chunkSize >= rem.length) Stream.chunk(Chunk.bytes(rem.getBytes))
          else {
            val (out, remainder) = rem.splitAt(chunkSize)
            Stream.chunk(Chunk.bytes(out.getBytes)) ++ go(remainder)
          }
        }
        go(data)
      }
    }

   "arbitrary.streams" in forAll { (stream: Stream[Task, Byte]) =>

      val example = stream.runLog.unsafeRun()

      val fromInputStream =
        stream.through(toInputStream).evalMap { is =>
          // consume in same thread pool. Production application should never do this,
          // instead they have to fork this to dedicated thread pool
          val buff = Array.ofDim[Byte](20)
          @tailrec
          def go(acc: Vector[Byte]): Task[Vector[Byte]] = {
            is.read(buff) match {
              case -1 => Task.now(acc)
              case read => go(acc ++ buff.take(read))
            }
          }
          go(Vector.empty)
        }.runLog.map(_.flatten).unsafeRun()

      example shouldBe fromInputStream

    }


    "upstream.is.closed" in  {
      var closed: Boolean = false
      val s: Stream[Task, Byte] = Stream(1.toByte).onFinalize(Task.delay(closed = true))

      s.through(toInputStream).run.unsafeRun()

      closed shouldBe true
    }

    "upstream.is.force-closed" in  {
      var closed: Boolean = false
      val s: Stream[Task, Byte] = Stream(1.toByte).onFinalize(Task.delay(closed = true))

      val result =
        s.through(toInputStream).evalMap { is =>
          Task.delay {
            is.close()
            closed // verifies that once close() terminates upstream was already cleaned up
          }
        }.runLog.unsafeRun()

      result shouldBe Vector(true)
    }

  }


}
