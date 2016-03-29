package fs2

import fs2.util.{Free,RealSupertype,Sub1}
import Async.Future

/**
 * A stream producing output of type `O`, which may evaluate `F`
 * effects. If `F` is `Nothing` or `[[fs2.Pure]]`, the stream is pure.
 */
abstract class Stream[+F[_],+O] extends StreamOps[F,O] { self =>
  import Stream.Handle

  def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2]

  final def fetchAsync[F2[_],O2>:O](implicit F2: Async[F2], S: Sub1[F,F2], T: RealSupertype[O,O2]): Stream[F2, Future[F2,Stream[F2,O2]]] =
    Stream.mk { get[F2,O2].fetchAsync.map(_ map (Stream.mk(_))) }

  override final def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F,O2] =
    Stream.mk { get mapChunks f }

  override final def map[O2](f: O => O2): Stream[F,O2] = mapChunks(_ map f)

  override final def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    get.runFold(z)(f)

  final def step: Pull[F,Nothing,Step[Chunk[O],Handle[F,O]]] =
    new Pull(get.step.map { o => Free.pure {
      o.map(_.right.map(s => s.copy(tail = new Handle(List(), Stream.mk(s.tail)))))
    }})

  final def stepAsync[F2[_],O2>:O](
    implicit S: Sub1[F,F2], F2: Async[F2], T: RealSupertype[O,O2])
    : Pull[F2,Nothing,Future[F2,Pull[F2,Nothing,Step[Chunk[O2], Handle[F2,O2]]]]]
    =
    fetchAsync[F2,O2].map(_ map (_.step)).step.flatMap { case Step(hd,tl) =>
      hd.uncons.map { case (sf,_) => Pull.pure(sf) }.getOrElse(Pull.done)
    }

  def uncons: Stream[F, Option[Step[Chunk[O], Stream[F,O]]]] =
    Stream.mk { get.uncons.map(_ map { case Step(hd,tl) => Step(hd, Stream.mk(tl)) }) }
}

object Stream extends Streams[Stream] with StreamDerived {
  type Pull[+F[_],+W,+R] = fs2.Pull[F,W,R]
  val Pull = fs2.Pull

  class Handle[+F[_],+O](private[fs2] val buffer: List[Chunk[O]],
                         private[fs2] val underlying: Stream[F,O]) {
    private[fs2] def stream: Stream[F,O] = {
      def go(buffer: List[Chunk[O]]): Stream[F,O] = buffer match {
        case List() => underlying
        case c :: buffer => ??? // chunk(c) ++ go(buffer)
      }
      go(buffer)
    }
    def map[O2](f: O => O2) = new Handle(buffer.map(_ map f), underlying map f)
  }
  object Handle {
    def empty[F[_],W]: Handle[F,W] = new Handle(List(), Stream.empty)
  }

  def flatMap[F[_],O,O2](s: Stream[F,O])(f: O => Stream[F,O2]): Stream[F,O2] =
    Stream.mk { s.get flatMap (o => f(o).get) }

  def onError[F[_],O](s: Stream[F,O])(h: Throwable => Stream[F,O]): Stream[F,O] =
    Stream.mk { s.get onError (e => h(e).get) }

  def append[F[_], A](a: Stream[F,A],b: => Stream[F,A]): Stream[F,A] = ???
  def await[F[_], A](h: Stream.Handle[F,A]): Stream.Pull[F,Nothing,Step[Chunk[A],Stream.Handle[F,A]]] = ???
  def awaitAsync[F[_], A](h: Stream.Handle[F,A])(implicit F: Async[F]): Stream.Pull[F,Nothing,Stream.AsyncStep[F,A]] = ???
  def bracket[F[_], R, A](acquire: F[R])(use: R => Stream[F,A],release: R => F[Unit]): Stream[F,A] = ???
  def chunk[F[_], A](as: Chunk[A]): Stream[F,A] = ???
  def eval[F[_], A](fa: F[A]): Stream[F,A] = ???
  def fail[F[_]](e: Throwable): Stream[F,Nothing] = ???
  def open[F[_], A](s: Stream[F,A]): Stream.Pull[F,Nothing,Stream.Handle[F,A]] = ???
  def push[F[_], A](h: Stream.Handle[F,A])(c: Chunk[A]): Stream.Handle[F,A] = ???
  def runFold[F[_], A, B](p: Stream[F,A],z: B)(f: (B, A) => B): util.Free[F,B] = ???
  def translate[F[_], G[_], A](s: Stream[F,A])(u: util.~>[F,G]): Stream[G,A] = ???

  private[fs2]
  def mk[F[_],O](s: StreamCore[F,O]): Stream[F,O] = new Stream[F,O] {
    def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2] =
      s.covary[F2].covaryOutput[O2]
  }

}

