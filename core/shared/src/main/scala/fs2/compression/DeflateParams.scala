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

package fs2.compression

/** Deflate algorithm parameters.
  */
sealed trait DeflateParams {

  /** Size of the internal buffer. Default size is 32 KB.
    */
  val bufferSize: Int

  /** Compression header. Defaults to [[ZLibParams.Header.ZLIB]].
    */
  val header: ZLibParams.Header

  /** Compression level. Default level is [[java.util.zip.Deflater.DEFAULT_COMPRESSION]].
    */
  val level: DeflateParams.Level

  /** Compression strategy. Default strategy is [[java.util.zip.Deflater.DEFAULT_STRATEGY]].
    */
  val strategy: DeflateParams.Strategy

  /** Compression flush mode. Default flush mode is [[java.util.zip.Deflater.NO_FLUSH]].
    */
  val flushMode: DeflateParams.FlushMode

  private[compression] val bufferSizeOrMinimum: Int = bufferSize.max(128)
}

object DeflateParams {

  def apply(
      bufferSize: Int = 1024 * 32,
      header: ZLibParams.Header = ZLibParams.Header.ZLIB,
      level: DeflateParams.Level = DeflateParams.Level.DEFAULT,
      strategy: DeflateParams.Strategy = DeflateParams.Strategy.DEFAULT,
      flushMode: DeflateParams.FlushMode = DeflateParams.FlushMode.DEFAULT
  ): DeflateParams =
    DeflateParamsImpl(bufferSize, header, level, strategy, flushMode)

  private case class DeflateParamsImpl(
      bufferSize: Int,
      header: ZLibParams.Header,
      level: DeflateParams.Level,
      strategy: DeflateParams.Strategy,
      flushMode: DeflateParams.FlushMode
  ) extends DeflateParams

  sealed abstract class Level(private[compression] val juzDeflaterLevel: Int)
  case object Level {
    private[fs2] def apply(level: Int): Level =
      level match {
        case DEFAULT.juzDeflaterLevel => Level.DEFAULT
        case ZERO.juzDeflaterLevel    => Level.ZERO
        case ONE.juzDeflaterLevel     => Level.ONE
        case TWO.juzDeflaterLevel     => Level.TWO
        case THREE.juzDeflaterLevel   => Level.THREE
        case FOUR.juzDeflaterLevel    => Level.FOUR
        case FIVE.juzDeflaterLevel    => Level.FIVE
        case SIX.juzDeflaterLevel     => Level.SIX
        case SEVEN.juzDeflaterLevel   => Level.SEVEN
        case EIGHT.juzDeflaterLevel   => Level.EIGHT
        case NINE.juzDeflaterLevel    => Level.NINE
      }

    case object DEFAULT extends Level(juzDeflaterLevel = -1)
    case object BEST_SPEED extends Level(juzDeflaterLevel = 1)
    case object BEST_COMPRESSION extends Level(juzDeflaterLevel = 9)
    case object NO_COMPRESSION extends Level(juzDeflaterLevel = 0)
    case object ZERO extends Level(juzDeflaterLevel = 0)
    case object ONE extends Level(juzDeflaterLevel = 1)
    case object TWO extends Level(juzDeflaterLevel = 2)
    case object THREE extends Level(juzDeflaterLevel = 3)
    case object FOUR extends Level(juzDeflaterLevel = 4)
    case object FIVE extends Level(juzDeflaterLevel = 5)
    case object SIX extends Level(juzDeflaterLevel = 6)
    case object SEVEN extends Level(juzDeflaterLevel = 7)
    case object EIGHT extends Level(juzDeflaterLevel = 8)
    case object NINE extends Level(juzDeflaterLevel = 9)
  }

  sealed abstract class Strategy(private[compression] val juzDeflaterStrategy: Int)
  case object Strategy {
    private[fs2] def apply(strategy: Int): Strategy =
      strategy match {
        case DEFAULT.juzDeflaterStrategy      => Strategy.DEFAULT
        case FILTERED.juzDeflaterStrategy     => Strategy.FILTERED
        case HUFFMAN_ONLY.juzDeflaterStrategy => Strategy.HUFFMAN_ONLY
      }

    case object DEFAULT extends Strategy(juzDeflaterStrategy = 0)
    case object BEST_SPEED extends Strategy(juzDeflaterStrategy = 2)
    case object BEST_COMPRESSION extends Strategy(juzDeflaterStrategy = 0)
    case object FILTERED extends Strategy(juzDeflaterStrategy = 1)
    case object HUFFMAN_ONLY extends Strategy(juzDeflaterStrategy = 2)
  }

  sealed abstract class FlushMode(private[compression] val juzDeflaterFlushMode: Int)
  case object FlushMode {
    private[fs2] def apply(flushMode: Int): FlushMode =
      flushMode match {
        case DEFAULT.juzDeflaterFlushMode    => FlushMode.NO_FLUSH
        case SYNC_FLUSH.juzDeflaterFlushMode => FlushMode.SYNC_FLUSH
        case FULL_FLUSH.juzDeflaterFlushMode => FlushMode.FULL_FLUSH
      }

    case object DEFAULT extends FlushMode(juzDeflaterFlushMode = 0)
    case object BEST_SPEED extends FlushMode(juzDeflaterFlushMode = 3)
    case object BEST_COMPRESSION extends FlushMode(juzDeflaterFlushMode = 0)
    case object NO_FLUSH extends FlushMode(juzDeflaterFlushMode = 0)
    case object SYNC_FLUSH extends FlushMode(juzDeflaterFlushMode = 2)
    case object FULL_FLUSH extends FlushMode(juzDeflaterFlushMode = 3)
  }

  /** Reasonable defaults for most applications.
    */
  val DEFAULT: DeflateParams = DeflateParams()

  /** Best speed for real-time, intermittent, fragmented, interactive or discontinuous streams.
    */
  val BEST_SPEED: DeflateParams = DeflateParams(
    level = Level.BEST_SPEED,
    strategy = Strategy.BEST_SPEED,
    flushMode = FlushMode.BEST_SPEED
  )

  /** Best compression for finite, complete, readily-available, continuous or file streams.
    */
  val BEST_COMPRESSION: DeflateParams = DeflateParams(
    bufferSize = 1024 * 128,
    level = Level.BEST_COMPRESSION,
    strategy = Strategy.BEST_COMPRESSION,
    flushMode = FlushMode.BEST_COMPRESSION
  )
}
