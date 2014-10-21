package scalaz.stream

import scalaz.Equal

package object split {
  def splitOn[A: Equal](seq: Vector[A]): Process1[A, Vector[A]] =
    Splitter.onSubsequence(seq).dropDelims.split

  def splitOneOf[A: Equal](delims: Vector[A]): Process1[A, Vector[A]] =
    Splitter.oneOf(delims).dropDelims.split

  def splitWhen[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.split

  def endBy[A: Equal](seq: Vector[A]): Process1[A, Vector[A]] =
    Splitter.onSubsequence(seq).dropDelims.dropFinalBlank.split

  def endByOneOf[A: Equal](delims: Vector[A]): Process1[A, Vector[A]] =
    Splitter.oneOf(delims).dropDelims.dropFinalBlank.split

  def wordsBy[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropDelims.dropBlanks.split

  def linesBy[A](pred: A => Boolean): Process1[A, Vector[A]] =
    Splitter.whenElt(pred).dropFinalBlank.dropBlanks.split
}
