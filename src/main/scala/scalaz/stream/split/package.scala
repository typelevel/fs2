package scalaz.stream

import scalaz.Equal

package object split {
  /**
   * A convenience method that splits on a given subsequence, dropping
   * delimiters.
   */
  def splitOn[A: Equal](seq: Vector[A]): Process1[A, Vector[A]] =
    Splitter.onSubsequence(seq).dropDelims.split

  /**
   * A convenience method that splits on any of the given elements, dropping
   * delimiters.
   */
  def splitOneOf[A: Equal](delims: Vector[A]): Process1[A, Vector[A]] =
    Splitter.oneOf(delims).dropDelims.split

  /**
   * A convenience method that splits on elements satisfying the given
   * predicate, dropping delimiters.
   */
  def splitWhen[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.split

  /**
   * A convenience method that splits into chunks terminated by the given
   * subsequence, dropping delimiters.
   */
  def endBy[A: Equal](seq: Vector[A]): Process1[A, Vector[A]] =
    Splitter.onSubsequence(seq).dropDelims.dropFinalBlank.split

  /**
   * A convenience method that splits into chunks terminated by one of the given
   * elements, dropping delimiters.
   */
  def endByOneOf[A: Equal](delims: Vector[A]): Process1[A, Vector[A]] =
    Splitter.oneOf(delims).dropDelims.dropFinalBlank.split

  /**
   * A convenience method that splits into words, with word boundaries indicated
   * by the given predicate, dropping delimiters.
   */
  def wordsBy[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.dropBlanks.split

  /**
   * A convenience method that splits into lines, with line boundaries indicated 
   * the given predicate, dropping delimiters.
   */
  def linesBy[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropFinalBlank.dropBlanks.split
}
