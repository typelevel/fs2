package fs2

import fs2.util.{RealSupertype,Sub1}

object Stream1 {
  def apply[F[_],O](s: StreamCore[F,O]): Stream1[F,O] = new Stream1[F,O] {
    def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2] =
      s.covary[F2].covaryOutput[O2]
  }
}

abstract class Stream1[+F[_],+O] { self =>
  def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2]

  def flatMap[F2[_],O2](f: O => Stream1[F2,O2])(implicit S1: Sub1[F,F2]): Stream1[F2,O2] =
    Stream1 { get flatMap (o => f(o).get) }

  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream1[F,O2] =
    Stream1 { get mapChunks f }

  def map[O2](f: O => O2): Stream1[F,O2] = mapChunks(_ map f)

  def onError[F2[_],O2>:O](h: Throwable => Stream1[F2,O2])(implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): Stream1[F2,O2] =
    Stream1 { get[F2,O2] onError (e => h(e).get) }

  def stepAsync[F2[_],O2>:O](implicit F2: Async[F2], S: Sub1[F,F2], T: RealSupertype[O,O2]): Stream1[F2, Async.Future[F2,Stream1[F2,O2]]] =
    Stream1 { get[F2,O2].stepAsync.map(_ map (Stream1(_))) }

  def uncons: Stream1[F, Option[Step[Chunk[O], Stream1[F,O]]]] =
    Stream1 { get.uncons.map(_ map { case Step(hd,tl) => Step(hd, Stream1(tl)) }) }
}
