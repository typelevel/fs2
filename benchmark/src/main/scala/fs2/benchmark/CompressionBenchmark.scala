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

package fs2.benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

import scala.util.Random

import fs2.{Chunk, Pipe, Stream}
import fs2.compression._

import CompressionBenchmark._

@State(Scope.Thread)
class CompressionBenchmark {

  @Param(Array("true", "false"))
  var withRandomBytes: Boolean = _

  @Benchmark
  def deflate(): Byte =
    benchmark(
      randomBytes,
      zeroBytes,
      Compression[IO].deflate(DeflateParams(bufferSize = bufferSize))
    )

  @Benchmark
  def inflate(): Byte =
    benchmark(
      randomBytesDeflated,
      zeroBytesDeflated,
      Compression[IO].inflate(InflateParams(bufferSize = bufferSize))
    )

  @Benchmark
  def gzip(): Byte =
    benchmark(randomBytes, zeroBytes, Compression[IO].gzip(bufferSize = bufferSize))

  @Benchmark
  def gunzip(): Byte =
    if (withRandomBytes)
      lastThrough2(randomBytesGzipped, Compression[IO].gunzip(bufferSize = bufferSize))
    else lastThrough2(zeroBytesGzipped, Compression[IO].gunzip(bufferSize = bufferSize))

  private def benchmark(
      randomInput: Array[Byte],
      zeroInput: Array[Byte],
      pipe: Pipe[IO, Byte, Byte]
  ): Byte =
    if (withRandomBytes) lastThrough(randomInput, pipe)
    else lastThrough(zeroInput, pipe)

  private def lastThrough(input: Array[Byte], pipe: Pipe[IO, Byte, Byte]): Byte =
    Stream
      .chunk[IO, Byte](Chunk.array(input))
      .through(pipe)
      .compile
      .last
      .unsafeRunSync()
      .get

  private def lastThrough2(input: Array[Byte], pipe: Pipe[IO, Byte, GunzipResult[IO]]): Byte =
    Stream
      .chunk[IO, Byte](Chunk.array(input))
      .through(pipe)
      .flatMap(_.content)
      .compile
      .last
      .unsafeRunSync()
      .get

}

object CompressionBenchmark {

  private val bytes: Int = 1024 * 1024
  private val bufferSize: Int = 32 * 1024

  private val randomBytes: Array[Byte] = {
    val random: Random = new Random(7919)
    val buffer = Array.ofDim[Byte](bytes)
    random.nextBytes(buffer)
    buffer
  }

  private val zeroBytes: Array[Byte] =
    Array.fill(bytes)(0.toByte)

  private val randomBytesDeflated: Array[Byte] =
    through(randomBytes, Compression[IO].deflate(DeflateParams(bufferSize = bufferSize)))

  private val zeroBytesDeflated: Array[Byte] =
    through(zeroBytes, Compression[IO].deflate(DeflateParams(bufferSize = bufferSize)))

  private val randomBytesGzipped: Array[Byte] =
    through(randomBytes, Compression[IO].gzip(bufferSize = bufferSize))

  private val zeroBytesGzipped: Array[Byte] =
    through(zeroBytes, Compression[IO].gzip(bufferSize = bufferSize))

  private def through(input: Array[Byte], pipe: Pipe[IO, Byte, Byte]): Array[Byte] =
    Stream
      .chunk[IO, Byte](Chunk.array(input))
      .through(pipe)
      .compile
      .to(Array)
      .unsafeRunSync()

}
