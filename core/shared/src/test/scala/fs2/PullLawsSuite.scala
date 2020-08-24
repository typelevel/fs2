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

// TODO
// package fs2

// import cats.Eq
// import cats.effect.IO
// import cats.effect.laws.discipline.arbitrary._
// import cats.effect.laws.util.TestContext
// import cats.effect.laws.util.TestInstances._
// import cats.implicits._
// import cats.effect.laws.discipline._

// class PullLawsSuite extends Fs2Suite {
//   implicit val ec: TestContext = TestContext()

//   implicit def eqPull[O: Eq, R: Eq]: Eq[Pull[IO, O, R]] = {
//     def normalize(p: Pull[IO, O, R]): IO[Vector[Either[O, R]]] =
//       p.mapOutput(Either.left(_)).flatMap(r => Pull.output1(Right(r))).stream.compile.toVector

//     Eq.instance((x, y) => Eq.eqv(normalize(x), normalize(y)))
//   }

//   checkAll("Sync[Pull[F, O, *]]", SyncTests[Pull[IO, Int, *]].sync[Int, Int, Int])
// }
