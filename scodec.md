# Scodec 

The `fs2-scodec` library provides the ability to do streaming binary encoding and decoding, powered by [scodec](https://github.com/scodec/scodec). It was originally called [scodec-stream](https://github.com/scodec/scodec-stream) and released independently for many years before being imported in to fs2.

```scala
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

    val filePath = Path("largefile.bin")

    val s: Stream[IO, ByteVector] =
      Files[IO].readAll(filePath).through(frames.toPipeByte)

    s.compile.count.flatMap(cnt => IO.println(s"Read $cnt frames."))
  }
}
```

When run, this program will incrementally read chunks from "largefile.bin", then decode a stream of frames, where each frame is expected to begin with a number of bytes specified as a 32-bit signed int (the `int32` codec), followed by a frame payload of that many bytes.

This library provides two main types: `StreamDecoder` and `StreamEncoder`. Each have various constructors and combinators to build instances up from regular scodec `Decoder` and `Encoder` values. Once built, they are typically converted to pipes via the `toPipeByte` method. In the example above, the `frames` decoder is converted to a `Pipe[IO, Byte, ByteVector]`.