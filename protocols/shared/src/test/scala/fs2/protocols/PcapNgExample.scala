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
import fs2.protocols.pcapng.{BodyBlock, SectionHeaderBlock}

object PcapNgExample extends IOApp.Simple {

  def run: IO[Unit] =
    Files[IO]
      .readAll(Path("/Users/anikiforov/http-ex.pcap"))
      .through(streamDecoder.toPipeByte)
      .debug()
      .compile
      .drain

  private val streamDecoder: StreamDecoder[(SectionHeaderBlock, BodyBlock)] =
    for {
      header <- StreamDecoder.once(SectionHeaderBlock.codec)
      decoder = BodyBlock.decoder(header.ordering)
      block <- StreamDecoder.many(decoder)
    } yield (header, block)
}
