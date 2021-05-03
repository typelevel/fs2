package fs2

import org.scalacheck.Shrink

import scala.collection.immutable.LazyList

private[fs2] trait ChunkGeneratorsCompat {

  protected implicit def shrinkChunk[A]: Shrink[Chunk[A]] =
    Shrink.withLazyList[Chunk[A]](c => removeChunks(c.size, c))

  // The removeChunks function and the interleave function were ported from Scalacheck,
  // from Shrink.scala, licensed under a Revised BSD license.
  //
  // /*-------------------------------------------------------------------------*\
  // **  ScalaCheck                                                             **
  // **  Copyright (c) 2007-2016 Rickard Nilsson. All rights reserved.          **
  // **  http://www.scalacheck.org                                              **
  // **                                                                         **
  // **  This software is released under the terms of the Revised BSD License.  **
  // **  There is NO WARRANTY. See the file LICENSE for the full text.          **
  // \*------------------------------------------------------------------------ */

  private def removeChunks[A](size: Int, xs: Chunk[A]): LazyList[Chunk[A]] =
    if (xs.isEmpty) LazyList.empty
    else if (xs.size == 1) LazyList(Chunk.empty)
    else {
      val n1 = size / 2
      val n2 = size - n1
      lazy val xs1 = xs.take(n1)
      lazy val xs2 = xs.drop(n1)
      lazy val xs3 =
        for (ys1 <- removeChunks(n1, xs1) if !ys1.isEmpty) yield Chunk.Queue(ys1, xs2)
      lazy val xs4 =
        for (ys2 <- removeChunks(n2, xs2) if !ys2.isEmpty) yield Chunk.Queue(xs1, ys2)

      LazyList.cons(xs1, LazyList.cons(xs2, interleave(xs3, xs4)))
    }

  private def interleave[A](xs: LazyList[A], ys: LazyList[A]): LazyList[A] =
    if (xs.isEmpty) ys else if (ys.isEmpty) xs else xs.head +: ys.head +: (xs.tail ++ ys.tail)
}
