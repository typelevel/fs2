package streams

import Step._

object pull1 {
  import Stream.Handle

  // nb: methods are in alphabetical order

  def await[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]] =
    _.await

  def await1[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[I,Handle[F,I]]] =
    _.await1

  def awaitOption[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[Step[Chunk[I],Handle[F,I]]]] =
    h => h.await.map(Some(_)) or Pull.pure(None)

  def id[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    Pull.loop { (h: Handle[F,I]) =>
      for {
        chunk #: h <- h.await
        _ <- Pull.write(chunk)
      } yield h
    }

  def take[F[_],I](n: Int): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h => for {
      hd #: h <- if (n <= 0) Pull.done else h.await1
      _ <- Pull.write1(hd)
      tl <- take(n-1)(h)
    } yield tl
}
