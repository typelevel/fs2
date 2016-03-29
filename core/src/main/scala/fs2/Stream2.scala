package fs2

import fs2.util.{Free,RealSupertype,Sub1}
import Async.Future

object Stream1 {
  def apply[F[_],O](s: StreamCore[F,O]): Stream1[F,O] = new Stream1[F,O] {
    def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2] =
      s.covary[F2].covaryOutput[O2]
  }

  class Handle[+F[_],+O](private[fs2] val buffer: List[Chunk[O]],
                         private[fs2] val underlying: Stream1[F,O]) {
    private[fs2] def stream: Stream1[F,O] = {
      def go(buffer: List[Chunk[O]]): Stream1[F,O] = buffer match {
        case List() => underlying
        case c :: buffer => ??? // chunk(c) ++ go(buffer)
      }
      go(buffer)
    }
    def map[O2](f: O => O2) = new Handle(buffer.map(_ map f), underlying map f)
  }
}

/**
 * A stream producing output of type `O`, which may evaluate `F`
 * effects. If `F` is `Nothing` or `[[fs2.Pure]]`, the stream is pure.
 */
abstract class Stream1[+F[_],+O] { self =>
  import Stream1.Handle

  def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2]

  final def fetchAsync[F2[_],O2>:O](implicit F2: Async[F2], S: Sub1[F,F2], T: RealSupertype[O,O2]): Stream1[F2, Future[F2,Stream1[F2,O2]]] =
    Stream1 { get[F2,O2].fetchAsync.map(_ map (Stream1(_))) }

  final def flatMap[F2[_],O2](f: O => Stream1[F2,O2])(implicit S1: Sub1[F,F2]): Stream1[F2,O2] =
    Stream1 { get flatMap (o => f(o).get) }

  final def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream1[F,O2] =
    Stream1 { get mapChunks f }

  final def map[O2](f: O => O2): Stream1[F,O2] = mapChunks(_ map f)

  final def onError[F2[_],O2>:O](h: Throwable => Stream1[F2,O2])(implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): Stream1[F2,O2] =
    Stream1 { get[F2,O2] onError (e => h(e).get) }

  final def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    get.runFold(z)(f)

  final def step: Pull3[F,Nothing,Step[Chunk[O],Handle[F,O]]] =
    new Pull3(get.step.map { o => Free.pure {
      o.map(_.right.map(s => s.copy(tail = new Handle(List(), Stream1(s.tail)))))
    }})

  final def stepAsync[F2[_],O2>:O](
    implicit S: Sub1[F,F2], F2: Async[F2], T: RealSupertype[O,O2])
    : Pull3[F2,Nothing,Future[F2,Pull3[F2,Nothing,Step[Chunk[O2], Handle[F2,O2]]]]]
    =
    fetchAsync[F2,O2].map(_ map (_.step)).step.flatMap { case Step(hd,tl) =>
      hd.uncons.map { case (sf,_) => Pull3.pure(sf) }.getOrElse(Pull3.done)
    }

  def uncons: Stream1[F, Option[Step[Chunk[O], Stream1[F,O]]]] =
    Stream1 { get.uncons.map(_ map { case Step(hd,tl) => Step(hd, Stream1(tl)) }) }
}
