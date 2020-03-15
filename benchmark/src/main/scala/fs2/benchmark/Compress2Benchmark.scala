package fs2.benchmark

import cats.effect.IO
import fs2.{Chunk, Pipe, Stream, compress2}
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

import scala.util.Random
import Compress2Benchmark._
import fs2.compress2.GunzipResult

@State(Scope.Thread)
class Compress2Benchmark {

  @Param(Array("true", "false"))
  var withRandomBytes: Boolean = _

  @Benchmark
  def deflate(): Byte =
    benchmark(randomBytes, zeroBytes, compress2.deflate(bufferSize = bufferSize))

  @Benchmark
  def inflate(): Byte =
    benchmark(randomBytesDeflated, zeroBytesDeflated, compress2.inflate(bufferSize = bufferSize))

  @Benchmark
  def gzip(): Byte =
    benchmark(randomBytes, zeroBytes, compress2.gzip(bufferSize = bufferSize))

  @Benchmark
  def gunzip(): Byte =
    if (withRandomBytes)
      lastThrough2(randomBytesGzipped, compress2.gunzip[IO](bufferSize = bufferSize))
    else lastThrough2(zeroBytesGzipped, compress2.gunzip[IO](bufferSize = bufferSize))

  private def benchmark(
      randomInput: Array[Byte],
      zeroInput: Array[Byte],
      pipe: Pipe[IO, Byte, Byte]
  ): Byte =
    if (withRandomBytes) lastThrough(randomInput, pipe)
    else lastThrough(zeroInput, pipe)

  private def lastThrough(input: Array[Byte], pipe: Pipe[IO, Byte, Byte]): Byte =
    Stream
      .chunk[IO, Byte](Chunk.bytes(input))
      .through(pipe)
      .compile
      .last
      .unsafeRunSync
      .get

  private def lastThrough2(input: Array[Byte], pipe: Pipe[IO, Byte, GunzipResult[IO]]): Byte =
    Stream
      .chunk[IO, Byte](Chunk.bytes(input))
      .through(pipe)
      .flatMap(_.content)
      .compile
      .last
      .unsafeRunSync
      .get

}

object Compress2Benchmark {

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
    through(randomBytes, compress2.deflate(bufferSize = bufferSize))

  private val zeroBytesDeflated: Array[Byte] =
    through(zeroBytes, compress2.deflate(bufferSize = bufferSize))

  private val randomBytesGzipped: Array[Byte] =
    through(randomBytes, compress2.gzip(bufferSize = bufferSize))

  private val zeroBytesGzipped: Array[Byte] =
    through(zeroBytes, compress2.gzip(bufferSize = bufferSize))

  private def through(input: Array[Byte], pipe: Pipe[IO, Byte, Byte]): Array[Byte] =
    Stream
      .chunk[IO, Byte](Chunk.bytes(input))
      .through(pipe)
      .compile
      .to(Array)
      .unsafeRunSync

}
