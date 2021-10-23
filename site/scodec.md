# Scodec 

The `fs2-scodec` library provides the ability to do streaming binary encoding and decoding, powered by [scodec](https://github.com/scodec/scodec). It was originally called [scodec-stream](https://github.com/scodec/scodec-stream) and released independently for many years before being imported in to fs2.

```scala mdoc
import cats.effect.{IO, IOApp}
import scodec.bits._
import scodec.codecs._
import fs2.Stream
import fs2.interop.scodec._
import fs2.io.file.{Files, Path}

object Decode extends IOApp.Simple {

  def run = {
    val frames: StreamDecoder[ByteVector] =
      StreamDecoder.many(int32).flatMap { numBytes => StreamDecoder.once(bytes(numBytes)) }

    val filePath = Path("path/to/file")

    val s: Stream[IO, ByteVector] =
      Files[IO].readAll(filePath).through(frames.toPipeByte)

    s.compile.count.flatMap(cnt => IO.println(s"Read $cnt frames."))
  }
}
```