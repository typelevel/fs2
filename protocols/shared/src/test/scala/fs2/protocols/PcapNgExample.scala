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
package protocols

import cats.effect.{IO, IOApp}
import fs2.interop.scodec.StreamDecoder
import fs2.io.file.{Files, Path}
import fs2.protocols.pcapng.{BodyBlock, DummyBlock, SectionHeaderBlock}
import scodec.Decoder
import cats.syntax.all._

object PcapNgExample extends IOApp.Simple {

  def run: IO[Unit] =
    decode.compile.toList.flatMap(_.traverse_(IO.println))

  private def byteStream: Stream[IO, Byte] =
    Files[IO].readAll(Path("/Users/anikiforov/pcapng/many_interfaces.pcapng"))

  private def revealFailed =
    byteStream
      .through(streamDecoder.toPipeByte)
      .flatMap {
        case dummy: DummyBlock => Stream.emit(dummy)
        case _                 => Stream.empty
      }
      .debug()

  private def decode =
    byteStream.through(streamDecoder.toPipeByte)

  private val streamDecoder: StreamDecoder[BodyBlock] =
    for {
      hdr <- StreamDecoder.once(SectionHeaderBlock.codec)
      fallback = DummyBlock.codec(hdr.ordering)
      decoder = Decoder.choiceDecoder(BodyBlock.decoder(hdr.ordering), fallback)
      block <- StreamDecoder.many(decoder)
    } yield block
}
