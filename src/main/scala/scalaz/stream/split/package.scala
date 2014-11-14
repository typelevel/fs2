package scalaz.stream

import scalaz.Equal

package object split {
  /**
   * A convenience method that splits on a given subsequence, dropping
   * delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(1, 2, 3, 1, 3, 0, 4).pipe(splitOn(Vector(3, 0))).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2, 3, 1), Vector(4))
   * }}}
   */
  def splitOn[A: Equal](seq: Vector[A]): Process1[A, Vector[A]] =
    Splitter.onSubsequence(seq).dropDelims.split

  /**
   * A convenience method that splits on any of the given elements, dropping
   * delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(1, 2, 3, 4, 5, 6).pipe(splitOneOf(Vector(2, 4, 5))).toList
   * res0: List[Vector[Int]] = List(Vector(1), Vector(3), Vector(), Vector(6))
   * }}}
   */
  def splitOneOf[A: Equal](delims: Vector[A]): Process1[A, Vector[A]] =
    Splitter.oneOf(delims).dropDelims.split

  /**
   * A convenience method that splits on elements satisfying the given
   * predicate, dropping delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(1, 2, 3, 5).pipe(splitWhen(_ % 2 == 0)).toList
   * res0: List[Vector[Int]] = List(Vector(1), Vector(3, 5))
   * }}}
   */
  def splitWhen[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.split

  /**
   * A convenience method that splits into chunks terminated by the given
   * subsequence, dropping delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(1, 2, 0, 1, 5, 0, 7).pipe(endBy(Vector(0, 1))).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(5, 0, 7))
   * }}}
   */
  def endBy[A: Equal](seq: Vector[A]): Process1[A, Vector[A]] =
    Splitter.onSubsequence(seq).dropDelims.dropFinalBlank.split

  /**
   * A convenience method that splits into chunks terminated by one of the given
   * elements, dropping delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(1, 2, 0, 1, 5, 0, 7).pipe(endByOneOf(Vector(0))).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(1, 5), Vector(7))
   * }}}
   */
  def endByOneOf[A: Equal](delims: Vector[A]): Process1[A, Vector[A]] =
    Splitter.oneOf(delims).dropDelims.dropFinalBlank.split

  /**
   * A convenience method that splits into words, with word boundaries indicated
   * by the given predicate, dropping delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(0, 1, 2, 0, 1, 5, 0, 7).pipe(wordsBy(_ == 0)).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(1, 5), Vector(7))
   * }}}
   */
  def wordsBy[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.dropBlanks.split

  /**
   * A convenience method that splits into lines, with line boundaries indicated 
   * the given predicate, dropping delimiters.
   *
   * @example {{{
   * scala> import scalaz.std.anyVal._
   * scala> import scalaz.stream.Process
   * scala> Process(0, 1, 2, 0, 3, 4, 0).pipe(linesBy(_ == 0)).toList
   * res0: List[Vector[Int]] = List(Vector(), Vector(1, 2), Vector(3, 4))
   * }}}
   */
  def linesBy[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.dropFinalBlank.split
}
