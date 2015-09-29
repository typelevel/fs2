package fs2

import Stream.Handle
import Step.{#:}

object concurrent {

  def join[F[_],O](maxOpen: Int)(s: Stream[F,Stream[F,O]])(implicit F: Async[F])
  : Stream[F,O]
  = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    def go(s: Handle[F,Stream[F,O]],
           onlyOpen: Boolean, // `true` if `s` should be ignored
           open: Vector[F[Pull[F, Nothing, Step[Chunk[O], Handle[F,O]]]]])
    : Pull[F,O,Unit] =
      // A) Nothing's open; block to obtain a new open stream
      if (open.isEmpty) s.await1.flatMap { case sh #: s =>
        sh.open.flatMap(_.await).flatMap { step =>
          go(s, onlyOpen, open :+ F.pure(Pull.pure(step): Pull[F,Nothing,Step[Chunk[O],Handle[F,O]]]))
      }}
      // B) We have too many things open, or `s` is exhausted so we only consult `open`
      // race to obtain a step from each of the currently open handles
      else if (open.size >= maxOpen || onlyOpen)
        Pull.eval(indexedRace(open)) flatMap { case (p,i) =>
          p.optional.flatMap {
            case None => go(s, onlyOpen, open.patch(i, List(), 1)) // remove i from open
            case Some(out #: h) =>
              Pull.write(out) >> h.awaitAsync.flatMap { next => go(s, onlyOpen, open.updated(i,next)) }
          }
        }
      // C) Like B), but we are allowed to open more handles, so race opening a new handle
      // with pulling from already open handles
      else for {
        nextS <- s.await1Async
        piOrNewStream <- Pull.eval(F.race(indexedRace(open), nextS))
        _ <- piOrNewStream match {
          case Left((p, i)) => p.optional.flatMap {
            case None => go(s, onlyOpen, open.patch(i, List(), 1)) // remove i from open
            case Some(out #: h) =>
              Pull.write(out) >> h.awaitAsync.flatMap { next => go(s, onlyOpen, open.updated(i,next)) }
          }
          case Right(anotherOpen) =>
            anotherOpen.optional.flatMap {
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

}
