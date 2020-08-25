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

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary

trait MiscellaneousGenerators {
  val throwableGenerator: Gen[Throwable] =
    Gen.const(new Err)
  implicit val throwableArbitrary: Arbitrary[Throwable] =
    Arbitrary(throwableGenerator)

  def arrayGenerator[A: reflect.ClassTag](genA: Gen[A]): Gen[Array[A]] =
    Gen.chooseNum(0, 100).flatMap(n => Gen.listOfN(n, genA)).map(_.toArray)
  implicit def arrayArbitrary[A: Arbitrary: reflect.ClassTag]: Arbitrary[Array[A]] =
    Arbitrary(arrayGenerator(arbitrary[A]))

  def smallLists[A](genA: Gen[A]): Gen[List[A]] =
    Gen.posNum[Int].flatMap(n0 => Gen.listOfN(n0 % 20, genA))
}
