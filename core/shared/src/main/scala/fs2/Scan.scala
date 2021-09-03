/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

// Adapted from scodec-protocols, licensed under 3-clause BSD
package fs2

import cats.data.AndThen

final class Scan[S, -I, +O](
    val initial: S,
    private val transform_ : AndThen[(S, I), (S, Chunk[O])],
    private val onComplete_ : AndThen[S, Chunk[O]]
) {

  def transform(s: S, i: I): (S, Chunk[O]) = transform_((s, i))

  def transformAccumulate(s: S, c: Chunk[I]): (S, Chunk[O]) =
    // c.traverse(i => State(transform(_, i))).map(_.flatten).run(s).value
    c.foldLeft(s -> Chunk.empty[O]) { case ((s, acc), i) =>
      val (s2, os) = transform(s, i)
      (s2, acc ++ os)
    }

  def onComplete(s: S): Chunk[O] = onComplete_(s)

  def toPipe[F[_]]: Pipe[F, I, O] =
    _.pull
      .scanChunks(initial)(transformAccumulate)
      .flatMap(state => Pull.output(onComplete(state)))
      .stream

  def step(i: I): (Scan[S, I, O], Chunk[O]) = {
    val (s, os) = transform(initial, i)
    (new Scan(s, transform_, onComplete_), os)
  }

  def andThen[S2, O2](t: Scan[S2, O, O2]): Scan[(S, S2), I, O2] =
    Scan[(S, S2), I, O2]((initial, t.initial))(
      { case ((s, s2), i) =>
        val (sp, os) = transform(s, i)
        val (s2p, out) = t.transformAccumulate(s2, os)
        ((sp, s2p), out)
      },
      { case (s, s2) =>
        val (s3, out) = t.transformAccumulate(s2, onComplete(s))
        out ++ t.onComplete(s3)
      }
    )

  def map[O2](f: O => O2): Scan[S, I, O2] =
    new Scan(
      initial,
      transform_.andThen[(S, Chunk[O2])] { case (s, os) => (s, os.map(f)) },
      onComplete_.andThen(_.map(f))
    )

  def contramap[I2](f: I2 => I): Scan[S, I2, O] =
    new Scan(
      initial,
      AndThen[(S, I2), (S, I)] { case (s, i2) => (s, f(i2)) }.andThen(transform_),
      onComplete_
    )

  def imapState[S2](g: S => S2)(f: S2 => S): Scan[S2, I, O] =
    Scan[S2, I, O](g(initial))(
      { (s2, i) =>
        val (s3, os) = transform(f(s2), i)
        (g(s3), os)
      },
      AndThen(f).andThen(onComplete_)
    )

  def lens[I2, O2](get: I2 => I, set: (I2, O) => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      { (s, i2) =>
        val (s2, os) = transform(s, get(i2))
        (s2, os.map(s => set(i2, s)))
      },
      _ => Chunk.empty
    )

  def first[A]: Scan[S, (I, A), (O, A)] =
    lens(_._1, (t, o) => (o, t._2))

  def second[A]: Scan[S, (A, I), (A, O)] =
    lens(_._2, (t, o) => (t._1, o))

  def semilens[I2, O2](extract: I2 => Either[O2, I], inject: (I2, O) => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      (s, i2) =>
        extract(i2).fold(
          o2 => s -> Chunk.singleton(o2),
          i => {
            val (s2, os) = transform(s, i)
            (s2, os.map(o => inject(i2, o)))
          }
        ),
      _ => Chunk.empty
    )

  def semipass[I2, O2 >: O](extract: I2 => Either[O2, I]): Scan[S, I2, O2] =
    semilens(extract, (_, o) => o)

  def left[A]: Scan[S, Either[I, A], Either[O, A]] =
    semilens(_.fold(i => Right(i), a => Left(Right(a))), (_, o) => Left(o))

  def right[A]: Scan[S, Either[A, I], Either[A, O]] =
    semilens(_.fold(a => Left(Left(a)), i => Right(i)), (_, o) => Right(o))

  def or[S2, I2, O2 >: O](t: Scan[S2, I2, O2]): Scan[(S, S2), Either[I, I2], O2] =
    Scan[(S, S2), Either[I, I2], O2]((initial, t.initial))(
      { case ((s, s2), e) =>
        e match {
          case Left(i) =>
            val (sp, os) = transform(s, i)
            ((sp, s2), os)
          case Right(i2) =>
            val (s2p, o2s) = t.transform(s2, i2)
            ((s, s2p), o2s)
        }
      },
      { case (s, s2) => onComplete(s) ++ t.onComplete(s2) }
    )

  def either[S2, I2, O2](t: Scan[S2, I2, O2]): Scan[(S, S2), Either[I, I2], Either[O, O2]] =
    Scan[(S, S2), Either[I, I2], Either[O, O2]]((initial, t.initial))(
      { case ((s, s2), e) =>
        e match {
          case Left(i) =>
            val (sp, os) = transform(s, i)
            ((sp, s2), os.map(Left(_)))
          case Right(i2) =>
            val (s2p, o2s) = t.transform(s2, i2)
            ((s, s2p), o2s.map(Right(_)))
        }
      },
      { case (s, s2) => onComplete(s).map(Left(_)) ++ t.onComplete(s2).map(Right(_)) }
    )
}

object Scan {
  // def apply[S, I, O](initial: S, transform: AndThen[(S, I), (S, Chunk[O])], onComplete: AndThen[S, Chunk[O]]): Scan[S,I,O] =
  //   new Scan[S,I,O](initial, transform, onComplete)

  def apply[S, I, O](
      initial: S
  )(transform: (S, I) => (S, Chunk[O]), onComplete: S => Chunk[O]): Scan[S, I, O] =
    new Scan(initial, AndThen { case (s, i) => transform(s, i) }, AndThen(onComplete))

  def stateful[S, I, O](initial: S)(transform: (S, I) => (S, Chunk[O])): Scan[S, I, O] =
    apply(initial)(transform, _ => Chunk.empty)

  def stateful1[S, I, O](initial: S)(f: (S, I) => (S, O)): Scan[S, I, O] =
    stateful[S, I, O](initial) { (s, i) =>
      val (s2, o) = f(s, i); s2 -> Chunk.singleton(o)
    }

  def stateless[I, O](f: I => Chunk[O]): Scan[Unit, I, O] =
    stateful[Unit, I, O](())((u, i) => (u, f(i)))

  def lift[I, O](f: I => O): Scan[Unit, I, O] =
    stateless(i => Chunk.singleton(f(i)))
}
