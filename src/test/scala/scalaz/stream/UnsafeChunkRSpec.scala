package scalaz.stream

import java.io.{InputStream, ByteArrayInputStream}

import org.scalacheck.Prop.{ forAll, propBoolean, protect }
import org.scalacheck.Properties

import scalaz.concurrent.Task
import scalaz.stream.Process.ProcessSyntax

class UnsafeChunkRSpec extends Properties("io.unsafeChunkR") {
  property("reuses buffer") = protect {
    forAll { str: String =>
      val sink: Sink[Task, Array[Byte] => Task[Array[Byte]]] =
        channel lift { toTask =>
          val buffer: Array[Byte] = new Array[Byte](8)
          toTask(buffer).map { b =>
            if (!buffer.eq(b) && b.size == buffer.size)
              throw new IllegalStateException("different buffer!")
          }
        }
      io.unsafeChunkR(new ByteArrayInputStream(str.getBytes))
        .to(sink)
        .run
        .attemptRun
        .leftMap(t => throw t)
        .isRight
    }
  }

  property("repeats read() to fill buffer") = protect {
    val bytewiseInputStream = new InputStream {
      def read(): Int = 0

      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        if (b == null) throw new NullPointerException
        else if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException
        else if (len == 0) 0
        else {
          // read one zero
          b(off) = read().toByte
          1
        }
      }
    }

    forAll { str: String => // using Gen[String].map(_.getBytes) as a source of arrays that will fit in memory
      val f = (buf: Array[Byte]) => Task {
        if (buf.length != str.getBytes.length) throw new IllegalStateException("underfilled buffer")
      }
      Process.emit(str.getBytes).toSource
        .through(io.unsafeChunkR(bytewiseInputStream))
        .to(channel lift f)
        .run.attemptRun.isRight
    }
  }

}
