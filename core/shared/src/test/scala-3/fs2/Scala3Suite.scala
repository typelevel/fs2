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

class Scala3Suite extends Fs2Suite {

  group("chunk") {
    test("iarray") {
      assertEquals(Chunk.iarray(IArray(1, 2, 3)), Chunk(1, 2, 3))
    }

    test("toIArray") {
      assert(
        java.util.Arrays.equals(Chunk(1, 2, 3).toIArray.asInstanceOf[Array[Int]], Array(1, 2, 3))
      )
    }

    test("iarray andThen toIArray is identity") {
      val arr = IArray(1, 2, 3)
      assert(
        Chunk.iarray(arr).toIArray.asInstanceOf[Array[Int]] eq arr.asInstanceOf[Array[Int]]
      )
    }
  }

  group("compilation") {
    test("IArray") {
      val x: IArray[Int] = Stream(1, 2, 3).to(IArray)
      assertEquals(x.foldRight(Nil: List[Int])(_ :: _), List(1, 2, 3))
      Stream(1, 2, 3).compile.to(IArray)
    }
  }
}
