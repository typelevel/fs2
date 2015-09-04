package streams

import Step._

object pull1 {
  import Stream.Handle

  // nb: methods are in alphabetical order

  def await[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]] =
    _.await

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

  def awaitOption[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[Step[Chunk[I],Handle[F,I]]]] =
    h => h.await.map(Some(_)) or Pull.pure(None)

  /** Copy the next available chunk to the output. */
  def copy[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => h.await flatMap { case chunk #: h => Pull.write(chunk) >> Pull.pure(h) }

  /** Copy the next available element to the output. */
  def copy1[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => h.await1 flatMap { case hd #: h => Pull.write1(hd) >> Pull.pure(h) }

  /** Like `[[awaitN]]`, but leaves the buffered input unconsumed. */
  def fetchN[F[_],I](n: Int): Handle[F,I] => Pull[F,Nothing,Handle[F,I]] =
    h => awaitN(n)(h) map { case buf #: h => buf.reverse.foldLeft(h)(_ push _) }

  // def prefetch[F[_]:Async,I](n: Int): Handle[F,I] => Pull[F,Nothing,Pull[F,Nothing,Handle[F,I]]]
  // prefetched = h => for {
  //   hd #: h <- h.await
  //   p <- prefetch(10)(h)
  //   h <- Pull.write(hd) >> p
  //   next <- prefetched(h)
  // } yield next

  def id[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => for {
      chunk #: h <- h.await
      tl <- Pull.write(chunk) >> id(h)
    } yield tl

  def take[F[_],I](n: Int): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => for {
      chunk #: h <- if (n <= 0) Pull.done else awaitLimit(n)(h)
      _ <- Pull.write(chunk)
      tl <- take(n - chunk.size)(h)
    } yield tl
}
