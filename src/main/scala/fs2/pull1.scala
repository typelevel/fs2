package fs2

import Step._

private[fs2] trait pull1 {
  import Stream.Handle

  // nb: methods are in alphabetical order

  /** Await the next available `Chunk` from the `Handle`. The `Chunk` may be empty. */
  def await[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]] =
    _.await

  /** Await a single element from the `Handle`. */
  def await1[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[I,Handle[F,I]]] =
    _.await1

  /** Like `await`, but return a `Chunk` of no more than `maxChunkSize` elements. */
  def awaitLimit[F[_],I](maxChunkSize: Int)
    : Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]]
    = _.await.map { s =>
        if (s.head.size <= maxChunkSize) s
        else s.head.take(maxChunkSize) #: s.tail.push(s.head.drop(maxChunkSize))
      }

  /** Return a `List[Chunk[I]]` from the input whose combined size is exactly `n`. */
  def awaitN[F[_],I](n: Int)
    : Handle[F,I] => Pull[F,Nothing,Step[List[Chunk[I]],Handle[F,I]]]
    = h =>
        if (n <= 0) Pull.pure(List() #: h)
        else for {
          hd #: tl <- awaitLimit(n)(h)
          hd2 #: tl <- awaitN(n - hd.size)(tl)
        } yield (hd :: hd2) #: tl

  /** Await the next available chunk from the input, or `None` if the input is exhausted. */
  def awaitOption[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[Step[Chunk[I],Handle[F,I]]]] =
    h => h.await.map(Some(_)) or Pull.pure(None)

  /** Await the next available element from the input, or `None` if the input is exhausted. */
  def await1Option[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[Step[I,Handle[F,I]]]] =
    h => h.await1.map(Some(_)) or Pull.pure(None)

  /** Copy the next available chunk to the output. */
  def copy[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => h.await flatMap { case chunk #: h => Pull.write(chunk) >> Pull.pure(h) }

  /** Copy the next available element to the output. */
  def copy1[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => h.await1 flatMap { case hd #: h => Pull.write1(hd) >> Pull.pure(h) }

  /** Like `[[awaitN]]`, but leaves the buffered input unconsumed. */
  def fetchN[F[_],I](n: Int): Handle[F,I] => Pull[F,Nothing,Handle[F,I]] =
    h => awaitN(n)(h) map { case buf #: h => buf.reverse.foldLeft(h)(_ push _) }

  /** Write all inputs to the output of the returned `Pull`. */
  def id[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => for {
      chunk #: h <- h.await
      tl <- Pull.write(chunk) >> id(h)
    } yield tl

  /**
   * Write all inputs to the output of the returned `Pull`, transforming elements using `f`.
   * Works in a chunky fashion and creates a `Chunk.indexedSeq` for each mapped chunk.
   */
  def lift[F[_],W,W2](f: W => W2): Handle[F,W] => Pull[F,W2,Handle[F,W]] =
    h => for {
      chunk #: h <- h.await
      tl <- Pull.write(chunk map f) >> lift(f)(h)
    } yield tl

  /**
   * Like `[[await]]`, but runs the `await` asynchronously. A `flatMap` into
   * inner `Pull` logically blocks until this await completes.
   */
  def prefetch[F[_]:Async,I](h: Handle[F,I]): Pull[F,Nothing,Pull[F,Nothing,Handle[F,I]]] =
    h.awaitAsync map { fut =>
      Pull.eval(fut) flatMap { p =>
        p map { case hd #: h => h push hd }
      }
    }

  /**
   * Like `[[prefetch]]`, but continue fetching asynchronously as long as the number of
   * prefetched elements is less than `n`.
   */
  def prefetchN[F[_]:Async,I](n: Int)(h: Handle[F,I]): Pull[F,Nothing,Pull[F,Nothing,Handle[F,I]]] =
    if (n <= 0) Pull.pure(Pull.pure(h))
    else prefetch(h) map { p =>
      for {
        s <- p.flatMap(awaitLimit(n)).map(Some(_)) or Pull.pure(None)
        tl <- s match {
          case Some(hd #: h) => prefetchN(n - hd.size)(h) flatMap { tl => tl.map(_ push hd) }
          case None => Pull.pure(Handle.empty)
        }
      } yield tl
    }

  def take[F[_],I](n: Int): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => for {
      chunk #: h <- if (n <= 0) Pull.done else awaitLimit(n)(h)
      tl <- Pull.write(chunk) >> take(n - chunk.size)(h)
    } yield tl
}
