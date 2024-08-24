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
package hashing

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import hashing.openssl._

trait HashingSuitePlatform {
  def digest(algo: String, str: String): Digest = {
    val name = algo match {
      case "MD5"         => "MD5"
      case "SHA-224"     => "SHA224"
      case "SHA-256"     => "SHA256"
      case "SHA-384"     => "SHA384"
      case "SHA-512"     => "SHA512"
      case "SHA-512/224" => "SHA512-224"
      case "SHA-512/256" => "SHA512-256"
      case "SHA3-224"    => "SHA3-224"
      case "SHA3-256"    => "SHA3-256"
      case "SHA3-384"    => "SHA3-384"
      case "SHA3-512"    => "SHA3-512"
    }
    val bytes = str.getBytes
    val md = new Array[Byte](EVP_MAX_MD_SIZE)
    val size = stackalloc[CUnsignedInt]()
    val `type` = EVP_get_digestbyname((name + "\u0000").getBytes.atUnsafe(0))
    EVP_Digest(
      if (bytes.length > 0) bytes.atUnsafe(0) else null,
      bytes.length.toULong,
      md.atUnsafe(0),
      size,
      `type`,
      null
    )
    Digest(Chunk.array(md.take((!size).toInt)))
  }
}
