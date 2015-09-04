package fs2

import fs2.util.UF1
import Step.{#:}

trait Streams[Stream[+_[_],+_]] { self =>

  // list-like operations

  def empty[A]: Stream[Nothing,A] = chunk[A](Chunk.empty)

  def chunk[A](as: Chunk[A]): Stream[Nothing,A]

  def append[F[_],A](a: Stream[F,A], b: => Stream[F,A]): Stream[F,A]

  def flatMap[F[_],A,B](a: Stream[F,A])(f: A => Stream[F,B]): Stream[F,B]


  // evaluating effects

  def eval[F[_],A](fa: F[A]): Stream[F,A]


  // translating effects

  def translate[F[_],G[_],W](s: Stream[F,W])(u: UF1[F,G]): Stream[G,W]

  // failure and error recovery

  def fail(e: Throwable): Stream[Nothing,Nothing]

  def onError[F[_],A](p: Stream[F,A])(handle: Throwable => Stream[F,A]): Stream[F,A]

  // safe resource usage

  def bracket[F[_],R,A](acquire: F[R])(use: R => Stream[F,A], release: R => F[Unit]): Stream[F,A]

  // stepping a stream

  type Handle[+F[_],+_]
  type Pull[+F[_],+R,+O]

  def Pull: Pulls[Pull]

  type AsyncStep[F[_],A] = F[Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]]
  type AsyncStep1[F[_],A] = F[Pull[F, Nothing, Step[Option[A], Handle[F,A]]]]

  def push[F[_],A](h: Handle[F,A])(c: Chunk[A]): Handle[F,A]

  def await[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]

  def awaitAsync[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep[F,A]]

  def open[F[_],A](s: Stream[F,A]): Pull[F,Nothing,Handle[F,A]]

  // evaluation

  def runFold[F[_],A,B](p: Stream[F,A], z: B)(f: (B,A) => B): Free[F,B]
}

