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

package fs2.io.file.examples

import cats.kernel.{Group, Order}
import cats.syntax.all._
import cats.effect.kernel.Concurrent

import fs2.io.file._

import scala.concurrent.duration._

/** Cross platform (JVM and Node.js) examples of streaming file system computations.
  */
object Examples {

  /** Counts all the files in the tree starting at the supplied path. */
  def countFiles[F[_]: Files: Concurrent](path: Path): F[Long] =
    Files[F].walk(path).compile.count

  /** Tallies the total number of bytes of all files rooted at the supplied path. */
  def totalBytes[F[_]: Files: Concurrent](path: Path): F[Long] =
    Files[F].walk(path).evalMap(p => Files[F].size(p).handleError(_ => 0L)).compile.foldMonoid

  /** Now let's have some fun with abstract algebra!
    *
    * Let's define an operation pathWithLeastIn that walks all files rooted at the supplied path
    * and extracts a user defined "feature" from the attributes of each file. A feature is something
    * of interest, like the size of the file or the last modified time. We then return the entry
    * with the minimum value for the feature. E.g., if the feature is the last modified time, we'll
    * return the file that was modified furthest in the past.
    *
    * Our "feature" is the type parameter `A`, which must have an `Order` constraint so that we can
    * compare entries. For the implementation, we simply compute the attributes for each path and then
    * compile the resulting stream using a semigroup which selects the path with the lower extracted
    * feature.
    */
  def pathWithLeastIn[F[_]: Files: Concurrent, A: Order](
      path: Path,
      extractFeature: BasicFileAttributes => A
  ): F[Option[(Path, A)]] =
    Files[F]
      .walk(path)
      .evalMap(p => Files[F].getBasicFileAttributes(p).map(extractFeature).tupleLeft(p))
      .compile
      .foldSemigroup { case (x @ (_, xFeature), y @ (_, yFeature)) =>
        if (Order[A].lt(xFeature, yFeature)) x else y
      }

  /** We can also select the greatest feature, and in fact we can derive it from pathWithLeastIn!
    *
    * We have two options:
    * - call pathWithGreatestIn using the inverse of the feature, ensuring to "undo" the inverse on the
    *   result. This requires a `Group` constraint on A`
    * - pass a different `Order` instance which reverses the order
    *
    * The second option is better for callers and is more general (it doesn't require a `Group[A]` instance).
    */
  def pathWithGreatestIn[F[_]: Files: Concurrent, A: Order](
      path: Path,
      extractFeature: BasicFileAttributes => A
  ): F[Option[(Path, A)]] =
    pathWithLeastIn(path, extractFeature)(Files[F], Concurrent[F], (x, y) => Order[A].compare(y, x))

  /** For completeness, here's what it would look like using `Group`. */
  def pathWithGreatestInViaGroup[F[_]: Files: Concurrent, A: Order: Group](
      path: Path,
      extractFeature: BasicFileAttributes => A
  ): F[Option[(Path, A)]] =
    pathWithLeastIn(path, attr => Group[A].inverse(extractFeature(attr))).map(_.map { case (p, a) =>
      (p, Group[A].inverse(a))
    })

  // Now let's use these to write some useful functions

  def oldestFile[F[_]: Files: Concurrent](path: Path): F[Option[(Path, FiniteDuration)]] =
    pathWithLeastIn(path, _.creationTime)

  def newestFile[F[_]: Files: Concurrent](path: Path): F[Option[(Path, FiniteDuration)]] =
    pathWithGreatestIn(path, _.creationTime)

  def mostRecentlyModifiedFile[F[_]: Files: Concurrent](
      path: Path
  ): F[Option[(Path, FiniteDuration)]] =
    pathWithGreatestIn(path, _.lastModifiedTime)

  def largestFile[F[_]: Files: Concurrent](path: Path): F[Option[(Path, Long)]] =
    pathWithGreatestIn(path, _.size)

  // Who said abstract algebra was useless??
}
