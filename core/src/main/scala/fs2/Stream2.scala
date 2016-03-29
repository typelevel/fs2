package fs2

import fs2.util.{RealSupertype,Sub1}

abstract class Stream1[+F[_],+O] { self =>
  def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2]

  def flatMap[F2[_],O2](f: O => Stream1[F2,O2])(implicit S1: Sub1[F,F2]): Stream1[F2,O2] =
    new Stream1[F2,O2] { def get[F3[_],O3>:O2](implicit S2: Sub1[F2,F3], T: RealSupertype[O2,O3])
      = Sub1.substStreamCore(self.get[F2,O]).flatMap(o => f(o).get).covaryOutput
    }

  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream1[F,O2] =
    new Stream1[F,O2] { def get[F3[_],O3>:O2](implicit S2: Sub1[F,F3], T: RealSupertype[O2,O3])
      = self.get[F3,O].mapChunks(f).covaryOutput
    }

  def map[O2](f: O => O2): Stream1[F,O2] = mapChunks(_ map f)

  def onError[F2[_],O2>:O](h: Throwable => Stream1[F2,O2])(implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): Stream1[F2,O2] =
    new Stream1[F2,O2] { def get[F3[_],O3>:O2](implicit S2: Sub1[F2,F3], T: RealSupertype[O2,O3])
      = Sub1.substStreamCore(self.get[F2,O2]).onError(e => h(e).get).covaryOutput
    }
}
