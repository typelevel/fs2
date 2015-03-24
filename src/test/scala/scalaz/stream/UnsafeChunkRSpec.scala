package scalaz.stream

import java.io.ByteArrayInputStream

import org.scalacheck.Prop.{ forAll, propBoolean, secure }
import org.scalacheck.Properties

import scalaz.concurrent.Task
import scalaz.stream.Process.ProcessSyntax

object UnsafeChunkRSpec extends Properties("io.unsafeChunkR") {
  property("reuses buffer") = secure {
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
}
