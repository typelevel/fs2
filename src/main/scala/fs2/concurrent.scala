package fs2

import Step.{#:}
import Stream.Handle
import Async.Future

object concurrent {

  def join[F[_]:Async,O](maxOpen: Int)(s: Stream[F,Stream[F,O]]): Stream[F,O] = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    def go(s: Handle[F,Stream[F,O]],
           onlyOpen: Boolean, // `true` if `s` should be ignored
           open: Vector[Future[F, Pull[F, Nothing, Step[Chunk[O], Handle[F,O]]]]])
    : Pull[F,O,Unit] =
      // A) Nothing's open; block to obtain a new open stream
      if (open.isEmpty) s.await1.flatMap { case sh #: s =>
        sh.open.flatMap(_.await).flatMap { step =>
          go(s, onlyOpen, open :+ Future.pure(Pull.pure(step): Pull[F,Nothing,Step[Chunk[O],Handle[F,O]]]))
      }}
      // B) We have too many things open, or `s` is exhausted so we only consult `open`
      // race to obtain a step from each of the currently open handles
      else if (open.size >= maxOpen || onlyOpen)
        Future.race(open).force.flatMap { winner =>
          winner.get.optional.flatMap {
            case None => go(s, onlyOpen, winner.delete) // winning `Pull` is done, remove it
            case Some(out #: h) =>
              // winner has more values, write these to output
              // and update the handle for the winning position
              Pull.output(out) >> h.awaitAsync.flatMap { next => go(s, onlyOpen, winner.replace(next)) }
          }
        }
      // C) Like B), but we are allowed to open more handles, so race opening a new handle
      // with pulling from already open handles
      else for {
        nextS <- s.await1Async
        elementOrNewStream <- Future.race(open).race(nextS).force
        _ <- elementOrNewStream match {
          case Left(winner) => winner.get.optional.flatMap {
            case None => go(s, onlyOpen, winner.delete)
            case Some(out #: h) =>
              Pull.output(out) >> h.awaitAsync.flatMap { next => go(s, onlyOpen, winner.replace(next)) }
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
}
