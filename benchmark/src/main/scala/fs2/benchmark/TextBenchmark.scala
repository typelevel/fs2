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
package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}

import java.nio.charset.Charset

@State(Scope.Thread)
class TextBenchmark {
  @Param(Array("128", "1024", "4096", "16384", "131072"))
  var asciiStringSize: Int = _

  var asciiBytes: Array[Byte] = _
  var strings: Array[String] = _

  @Param(Array("utf-8", "utf-16", "iso-2022-kr"))
  var charsetName: String = _
  var charset: Charset = _

  @Setup
  def setup(): Unit = {
    val rng = new java.util.Random(7919)
    asciiBytes = (0 until asciiStringSize).map { _ =>
      (rng.nextInt(126) + 1).toByte
    }.toArray
    strings = new String(asciiBytes)
      .grouped(asciiStringSize / 16) // chunk it up to be more real-world
      .toArray
    charset = Charset.forName(charsetName)
  }

  @Benchmark
  def asciiDecode(): String =
    Stream
      .emits(asciiBytes)
      .through(text.utf8.decode[IO])
      .compile
      .last
      .unsafeRunSync()
      .get

  @Benchmark
  def asciiEncode(): Array[Byte] =
    Stream
      .emits(strings)
      .through(text.encode(charset))
      .compile
      .to(Array)
}
