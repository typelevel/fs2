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

// Adapted from scodec-protocols, licensed under 3-clause BSD

package fs2.protocols
package pcap

import scodec.{Codec, Err}
import scodec.codecs._
import fs2.interop.scodec._
import fs2.timeseries._

import scala.concurrent.duration._

case class CaptureFile(header: GlobalHeader, records: Vector[Record])

object CaptureFile {
  implicit val codec: Codec[CaptureFile] = "capture-file" |
    Codec[GlobalHeader]
      .flatPrepend { hdr =>
        vector(Record.codec(hdr.ordering)).hlist
      }
      .as[CaptureFile]

  def payloadStreamDecoderPF[A](
      linkDecoders: PartialFunction[LinkType, StreamDecoder[A]]
  ): StreamDecoder[TimeStamped[A]] =
    payloadStreamDecoder(linkDecoders.lift)

  def payloadStreamDecoder[A](
      linkDecoders: LinkType => Option[StreamDecoder[A]]
  ): StreamDecoder[TimeStamped[A]] =
    streamDecoder { global =>
      linkDecoders(global.network) match {
        case None => Left(Err(s"unsupported link type ${global.network}"))
        case Some(decoder) =>
          Right { hdr =>
            decoder.map { value =>
              TimeStamped(hdr.timestamp + global.thiszone.toLong.seconds, value)
            }
          }
      }
    }

  def recordStreamDecoder: StreamDecoder[Record] =
    streamDecoder[Record] { global =>
      Right { hdr =>
        StreamDecoder.once(bits).map { bs =>
          Record(hdr.copy(timestampSeconds = hdr.timestampSeconds + global.thiszone), bs)
        }
      }
    }

  def streamDecoder[A](
      f: GlobalHeader => Either[Err, (RecordHeader => StreamDecoder[A])]
  ): StreamDecoder[A] = for {
    global <- StreamDecoder.once(GlobalHeader.codec)
    decoderFn <- f(global).fold(StreamDecoder.raiseError, StreamDecoder.emit)
    recordDecoder =
      RecordHeader.codec(global.ordering).flatMap { header =>
        StreamDecoder.isolate(header.includedLength * 8)(decoderFn(header)).strict
      }
    values <- StreamDecoder.many(recordDecoder).flatMap(x => StreamDecoder.emits(x))
  } yield values
}
