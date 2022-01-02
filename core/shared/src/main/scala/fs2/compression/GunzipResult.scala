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
package compression

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/** Gunzip decompression results including file properties and
  * decompressed content stream, used as follows:
  *   stream
  *     .through(gunzip[IO]())
  *     .flatMap { gunzipResult =>
  *       // Access properties here.
  *       gunzipResult.content
  *     }
  *
  * @param content Uncompressed content stream.
  * @param modificationTime Modification time of compressed file.
  * @param fileName File name.
  * @param comment File comment.
  */
class GunzipResult[F[_]](
    val modificationEpochTime: Option[FiniteDuration],
    val fileName: Option[String],
    val comment: Option[String],
    val content: Stream[F, Byte]
) extends Product
    with Serializable
    with Equals {

  def modificationTime: Option[Instant] =
    modificationEpochTime.map(e => Instant.ofEpochSecond(e.toSeconds))

  @deprecated(
    "use the default constructor with modificationTimeEpoch: Option[FiniteDuration]",
    "x.x"
  )
  def this(
      content: Stream[F, Byte],
      modificationTime: Option[Instant] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  ) = this(
    modificationTime.map(i => FiniteDuration(i.getEpochSecond, TimeUnit.SECONDS)),
    fileName,
    comment,
    content
  )

  @deprecated("GunzipResult is no longer a case class.", "x.x")
  def copy(
      content: Stream[F, Byte] = this.content,
      modificationTime: Option[Instant] = this.modificationTime,
      fileName: Option[String] = this.fileName,
      comment: Option[String] = this.comment
  ): GunzipResult[F] = new GunzipResult[F](
    content,
    modificationTime,
    fileName,
    comment
  )

  @deprecated("GunzipResult is no longer a case class.", "x.x")
  def productArity: Int = 4

  @deprecated("GunzipResult is no longer a case class.", "x.x")
  def productElement(n: Int): Any = n match {
    case 0 => content
    case 1 => modificationEpochTime
    case 2 => fileName
    case 3 => comment
  }

  def canEqual(other: Any): Boolean = other match {
    case _: GunzipResult[_] => true
    case _                  => false
  }

  override def equals(other: Any): Boolean = other match {
    case that: GunzipResult[_] =>
      (that.canEqual(this)) &&
        modificationEpochTime == that.modificationEpochTime &&
        fileName == that.fileName &&
        comment == that.comment &&
        content == that.content
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(content, modificationEpochTime, fileName, comment)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString = s"GunzipResult($content, $modificationEpochTime, $fileName, $comment)"

}

object GunzipResult {

  @deprecated("Use GunzipResult.create", "x.x")
  def apply[F[_]](
      content: Stream[F, Byte],
      modificationTime: Option[Instant] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  ): GunzipResult[F] = new GunzipResult(
    content,
    modificationTime,
    fileName,
    comment
  )

  private[compression] def create[F[_]](
      content: Stream[F, Byte],
      modificationTimeEpoch: Option[FiniteDuration] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  ): GunzipResult[F] = new GunzipResult(
    modificationTimeEpoch,
    fileName,
    comment,
    content
  )

  @deprecated("GunzipResult is no longer a case class.", "x.x")
  def unapply[F[_]](
      result: GunzipResult[F]
  ): Option[(Stream[F, Byte], Option[Instant], Option[String], Option[String])] =
    Some(
      (
        result.content,
        result.modificationTime,
        result.fileName,
        result.comment
      )
    )

}
