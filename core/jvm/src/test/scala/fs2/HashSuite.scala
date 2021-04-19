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

import cats.effect.IO
import cats.syntax.all._
import java.security.MessageDigest
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import hash._

class HashSuite extends Fs2Suite {
  def digest(algo: String, str: String): List[Byte] =
    MessageDigest.getInstance(algo).digest(str.getBytes).toList

  def checkDigest[A](h: Pipe[Pure, Byte, Byte], algo: String, str: String) = {
    val n =
      if (str.length > 0) Gen.choose(1, str.length).sample.getOrElse(1) else 1
    val s =
      if (str.isEmpty) Stream.empty
      else
        str.getBytes
          .grouped(n)
          .foldLeft(Stream.empty.covaryOutput[Byte])((acc, c) =>
            acc ++ Stream.chunk(Chunk.array(c))
          )

    assertEquals(s.through(h).toList, digest(algo, str))
  }

  group("digests") {
    test("md2")(forAll((s: String) => checkDigest(md2, "MD2", s)))
    test("md5")(forAll((s: String) => checkDigest(md5, "MD5", s)))
    test("sha1")(forAll((s: String) => checkDigest(sha1, "SHA-1", s)))
    test("sha256")(forAll((s: String) => checkDigest(sha256, "SHA-256", s)))
    test("sha384")(forAll((s: String) => checkDigest(sha384, "SHA-384", s)))
    test("sha512")(forAll((s: String) => checkDigest(sha512, "SHA-512", s)))
  }

  test("empty input") {
    assertEquals(Stream.empty.through(sha1).toList.size, 20)
  }

  test("zero or one output") {
    forAll { (lb: List[Array[Byte]]) =>
      val size = lb
        .foldLeft(Stream.empty.covaryOutput[Byte])((acc, b) => acc ++ Stream.chunk(Chunk.array(b)))
        .through(sha1)
        .toList
        .size
      assertEquals(size, 20)
    }
  }

  test("thread-safety") {
    val s = Stream
      .range(1, 100)
      .covary[IO]
      .flatMap(i => Stream.chunk(Chunk.array(i.toString.getBytes)))
      .through(sha512)
    for {
      once <- s.compile.toVector
      oneHundred <- Vector.fill(100)(s.compile.toVector).parSequence
    } yield assertEquals(oneHundred, Vector.fill(100)(once))
  }
}
