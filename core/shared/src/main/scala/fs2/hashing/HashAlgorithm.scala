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

/** Enumeration of hash algorithms.
  *
  * Note: The existence of an algorithm in this list does not guarantee it is supported
  * at runtime, as algorithm support is platform specific.
  *
  * The `Named` constructor allows specifying an algorithm name that's not in this list.
  * The supplied name will be passed to the underlying crypto provider when creating a
  * `Hasher`.
  *
  * Implementation note: this class is not sealed, and its constructor is private,  to
  * allow for addition of new cases in the future without breaking compatibility.
  */
abstract class HashAlgorithm private ()

object HashAlgorithm {
  case object MD5 extends HashAlgorithm
  case object SHA1 extends HashAlgorithm
  case object SHA224 extends HashAlgorithm
  case object SHA256 extends HashAlgorithm
  case object SHA384 extends HashAlgorithm
  case object SHA512 extends HashAlgorithm
  case object SHA512_224 extends HashAlgorithm
  case object SHA512_256 extends HashAlgorithm
  case object SHA3_224 extends HashAlgorithm
  case object SHA3_256 extends HashAlgorithm
  case object SHA3_384 extends HashAlgorithm
  case object SHA3_512 extends HashAlgorithm
  final case class Named(name: String) extends HashAlgorithm

  def BuiltIn: List[HashAlgorithm] = List(
    MD5,
    SHA1,
    SHA224,
    SHA256,
    SHA384,
    SHA512,
    SHA512_224,
    SHA512_256,
    SHA3_224,
    SHA3_256,
    SHA3_384,
    SHA3_512
  )
}
