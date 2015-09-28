package fs2

import Stream.Handle
import Step.{#:}

object wye {

  trait Wye[-I,-I2,+O] {
    def run[F[_]:Async]: (Stream[F,I], Stream[F,I2]) => Stream[F,O]
    def apply[F[_]:Async](s: Stream[F,I], s2: Stream[F,I2]): Stream[F,O] = run.apply(s, s2)
  }

  def concurrentJoin[F[_],O](maxOpen: Int)(s: Stream[F,Stream[F,O]])(implicit F: Async[F])
  : Stream[F,O]
  = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    def go(s: Handle[F,Stream[F,O]],
           onlyOpen: Boolean,
           open: Vector[F[Pull[F, Nothing, Step[Chunk[O], Handle[F,O]]]]])
    : Pull[F,O,Unit] =
      if (open.isEmpty) for {
        sh #: s <- s.await1
        h <- sh.open
        step <- h.await // if nothing's open, we block to obtain an open stream
        _ <- go(s, onlyOpen, open :+ F.pure(Pull.pure(step): Pull[F,Nothing,Step[Chunk[O],Handle[F,O]]]))
      } yield ()
      else if (open.size >= maxOpen || onlyOpen) for {
        (p, i) <- Pull.eval(indexedRace(open))
        _ <- (for {
          out #: h <- p /* Can fail if winning step is a completed stream. */
          next <- h.awaitAsync
          _ <- go(s, onlyOpen, open.updated(i, next))
        } yield ()) or go(s, onlyOpen, open.patch(i, List(), 1)) /* Remote `i` from `open`. */
      } yield ()
      else for {
        nextS <- s.await1Async
        piOrNewStream <- Pull.eval(F.race(indexedRace(open), nextS))
        _ <- piOrNewStream match {
          case Left((p, i)) => (for {
            out #: h <- p /* Can fail if winner is a completed stream. */
            next <- h.awaitAsync
            _ <- go(s, onlyOpen, open.updated(i, next))
          } yield ()) or go(s, onlyOpen, open.patch(i, List(), 1))
          case Right(anotherOpen) =>
            anotherOpen.map(Some(_)).or(Pull.pure(None)).flatMap {
              case Some(s2) => s2 match {
                case None #: s => go(s, true, open)
                case Some(s2) #: s => s2.open.flatMap { h2 =>
                  h2.awaitAsync.map(f => go(s, onlyOpen, open :+ f))
                }
              }
              case None => go(s, true, open)
            }
        }
      } yield ()
    s.open.flatMap { h => go(h, false, Vector.empty) }.run
  }

  def indexedRace[F[_],A](fs: Vector[F[A]])(implicit F: Async[F]): F[(A,Int)] =
    races(fs.zip(0 until fs.size) map { case (f,i) => F.map(f)((_,i)) })

  def races[F[_],A](fs: Vector[F[A]])(implicit F: Async[F]): F[A] =
    if (fs.isEmpty) throw new IllegalArgumentException("empty race")
    else if (fs.size == 1) fs.head
    else { val (left,right) = fs.splitAt(fs.size / 2)
           F.map(F.race(races(left), races(right)))(_.fold(identity, identity)) }

  // todo: supply an Async which is totally sequential?
}
