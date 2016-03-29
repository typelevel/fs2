package fs2

import fs2.util.{Free,RealSupertype,Sub1,~>}
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
        case c :: buffer => chunk(c) ++ go(buffer)
      }
      go(buffer)
    }
    def map[O2](f: O => O2) = new Handle(buffer.map(_ map f), underlying map f)
  }
  object Handle {
    def empty[F[_],W]: Handle[F,W] = new Handle(List(), Stream.empty)
  }

  def append[F[_], A](a: Stream[F,A], b: => Stream[F,A]) =
    Stream.mk { StreamCore.append(a.get, StreamCore.suspend(b.get)) }

  def await[F[_],W](h: Handle[F,W]) =
    h.buffer match {
      case List() => h.underlying.step
      case hb :: tb => Pull.pure(Step(hb, new Handle(tb, h.underlying)))
    }

  def awaitAsync[F[_],W](h: Handle[F,W])(implicit F: Async[F]) =
    h.buffer match {
      case List() => h.underlying.stepAsync
      case hb :: tb => Pull.pure(Future.pure(Pull.pure(Step(hb, new Handle(tb, h.underlying)))))
    }

  def bracket[F[_],R,A](r: F[R])(use: R => Stream[F,A], release: R => F[Unit]) = Stream.mk {
    StreamCore.acquire(r, release andThen (Free.eval)) flatMap (use andThen (_.get))
  }

  def chunk[F[_], A](as: Chunk[A]): Stream[F,A] =
    Stream.mk { StreamCore.chunk[F,A](as) }

  def eval[F[_], A](fa: F[A]): Stream[F,A] =
    Stream.mk { StreamCore.eval(fa) }

  def fail[F[_]](e: Throwable): Stream[F,Nothing] =
    Stream.mk { StreamCore.fail(e) }

  def flatMap[F[_],O,O2](s: Stream[F,O])(f: O => Stream[F,O2]): Stream[F,O2] =
    Stream.mk { s.get flatMap (o => f(o).get) }

  def onError[F[_],O](s: Stream[F,O])(h: Throwable => Stream[F,O]): Stream[F,O] =
    Stream.mk { s.get onError (e => h(e).get) }

  def open[F[_],W](s: Stream[F,W]) =
    Pull.pure(new Handle(List(), s))

  def push[F[_],W](h: Handle[F,W])(c: Chunk[W]) =
    if (c.isEmpty) h
    else new Handle(c :: h.buffer, h.underlying)

  def runFold[F[_], A, B](p: Stream[F,A], z: B)(f: (B, A) => B): Free[F,B] =
    p.runFold(z)(f)

  def translate[F[_],G[_],A](s: Stream[F,A])(u: F ~> G): Stream[G,A] =
    Stream.mk { s.get.translate(StreamCore.NT.T(u)) }

  private[fs2]
  def mk[F[_],O](s: StreamCore[F,O]): Stream[F,O] = new Stream[F,O] {
    def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2] =
      s.covary[F2].covaryOutput[O2]
  }
}
