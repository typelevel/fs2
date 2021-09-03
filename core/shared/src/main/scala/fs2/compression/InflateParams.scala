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

/** Inflate algorithm parameters. */
sealed trait InflateParams {

  /** Size of the internal buffer. Default size is 32 KB.
    */
  val bufferSize: Int

  /** Compression header. Defaults to [[ZLibParams.Header.ZLIB]]
    */
  val header: ZLibParams.Header

  private[fs2] val bufferSizeOrMinimum: Int = bufferSize.max(128)
}

object InflateParams {

  def apply(
      bufferSize: Int = 1024 * 32,
      header: ZLibParams.Header = ZLibParams.Header.ZLIB
  ): InflateParams =
    InflateParamsImpl(bufferSize, header)

  /** Reasonable defaults for most applications.
    */
  val DEFAULT: InflateParams = InflateParams()

  private case class InflateParamsImpl(
      bufferSize: Int,
      header: ZLibParams.Header
  ) extends InflateParams

}
