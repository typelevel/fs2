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

import java.io.ByteArrayOutputStream
import java.util.zip._

class JvmCompressionSuite extends CompressionSuite {

  def deflateStream(b: Array[Byte], level: Int, strategy: Int, nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val deflater = new Deflater(level, nowrap)
    deflater.setStrategy(strategy)
    val deflaterStream = new DeflaterOutputStream(byteArrayStream, deflater)
    deflaterStream.write(b)
    deflaterStream.close()
    byteArrayStream.toByteArray
  }

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val inflaterStream =
      new InflaterOutputStream(byteArrayStream, new Inflater(nowrap))
    inflaterStream.write(b)
    inflaterStream.close()
    byteArrayStream.toByteArray
  }

  group("maybeGunzip") {
    def maybeGunzip[F[_]: Compression](s: Stream[F, Byte]): Stream[F, Byte] =
      s.pull
        .unconsN(2, allowFewer = true)
        .flatMap {
          case Some((hd, tl)) =>
            if (hd == Chunk[Byte](0x1f, 0x8b.toByte))
              Compression[F].gunzip(128)(tl.cons(hd)).flatMap(_.content).pull.echo
            else tl.cons(hd).pull.echo
          case None => Pull.done
        }
        .stream

    test("not gzip") {
      forAllF { (s: Stream[Pure, Byte]) =>
        maybeGunzip[IO](s).compile.toList.assertEquals(s.toList)
      }
    }

    test("gzip") {
      forAllF { (s: Stream[Pure, Byte]) =>
        maybeGunzip[IO](Compression[IO].gzip()(s)).compile.toList.assertEquals(s.toList)
      }
    }
  }

}
