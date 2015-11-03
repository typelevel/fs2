package fs2

import Step.{#:}
import Stream.Handle
import Async.Future
import fs2.{Pull => P}


object concurrent {

  /**
   * Calls `open` on the two streams, then invokes `[[wye.either]]`.
   */
  def either[F[_]:Async,O,O2](s1: Stream[F,O], s2: Stream[F,O2]): Stream[F,Either[O,O2]] =
    P.run { s1.open.flatMap(h1 => s2.open.flatMap(h2 => wye.either(h1,h2))) }

  /**
   * Calls `open` on the two streams, then invokes `[[wye.merge]]`.
   */
  def merge[F[_]:Async,O](s1: Stream[F,O], s2: Stream[F,O]): Stream[F,O] =
    P.run { s1.open.flatMap(h1 => s2.open.flatMap(h2 => wye.merge(h1,h2))) }

  def join[F[_],O](maxOpen: Int)(s: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    def go(s: Handle[F,Stream[F,O]],
           onlyOpen: Boolean, // `true` if `s` should be ignored
           open: Vector[Future[F, Pull[F, Nothing, Step[Chunk[O], Handle[F,O]]]]])
    : Pull[F,O,Unit] =
      // A) Nothing's open; block to obtain a new open stream
      if (open.isEmpty) s.await1.flatMap { case sh #: s =>
        sh.open.flatMap { sh => sh.await.optional.flatMap {
          case Some(step) =>
            go(s, onlyOpen, open :+ Future.pure(P.pure(step): Pull[F,Nothing,Step[Chunk[O],Handle[F,O]]]))
          case None => go(s, onlyOpen, open)
        }}
      }
      // B) We have too many things open, or `s` is exhausted so we only consult `open`
      // race to obtain a step from each of the currently open handles
      else if (open.size >= maxOpen || onlyOpen) {
        Future.race(open).force.flatMap { winner =>
          winner.get.optional.flatMap {
            case None => go(s, onlyOpen, winner.delete) // winning `Pull` is done, remove it
            case Some(out #: h) =>
              // winner has more values, write these to output
              // and update the handle for the winning position
              P.output(out) >> h.awaitAsync.flatMap { next => go(s, onlyOpen, winner.replace(next)) }
          }
        }
      }
      // C) Like B), but we are allowed to open more handles, so race opening a new handle
      // with pulling from already open handles
      else for {
        nextS <- s.await1Async
        elementOrNewStream <- Future.race(open).race(nextS).force
        u <- elementOrNewStream match {
          case Left(winner) => winner.get.optional.flatMap {
            case None => go(s, onlyOpen, winner.delete)
            case Some(out #: h) =>
              P.output(out) >> h.awaitAsync.flatMap { next => go(s, onlyOpen, winner.replace(next)) }
          }
          case Right(anotherOpen) =>
            anotherOpen.optional.flatMap {
              case Some(s2) => s2 match {
                case None #: s => go(s, true, open)
                case Some(s2) #: s => s2.open.flatMap { h2 =>
                  h2.awaitAsync.flatMap { f => go(s, onlyOpen, open :+ f) }
                }
              }
              case None => go(s, true, open)
            }
        }
      } yield u
    s.open.flatMap { h => go(h, false, Vector.empty) }.run
  }
}
