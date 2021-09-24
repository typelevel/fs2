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

import cats.{Contravariant, Functor}
import cats.data.AndThen
import cats.arrow.Strong

/** A stateful transformation of the elements of a stream.
  *
  * A scan is primarily represented as a function `(S, I) => (S, Chunk[O])`.
  * Scans also have an initial state value of type `S` and the ability to emit
  * elements upon completion via a function `S => Chunk[O]`.
  *
  * A scan is built up incrementally via various combinators and then converted to
  * a pipe via `.toPipe`. For example, `s.through(Scan.lift(identity).toPipe) == s`.
  *
  * A scan is much less powerful than a pull. Scans cannot evaluate effects or terminate
  * early. These limitations allow combinators that are not possible on pulls though.
  * For example, the [[first]] method converts a `Scan[S, I, O]` to a `Scan[S, (I, A), (O, A)]`.
  * Critically, this method relies on the ability to feed a single `I` to the original scan
  * and collect the resulting `O` values, pairing each `O` with the `A` that was paired with `I`.
  */
final class Scan[S, -I, +O](
    val initial: S,
    private val transform_ : AndThen[(S, I), (S, Chunk[O])],
    private val onComplete_ : AndThen[S, Chunk[O]]
) {

  /** Transformation function. */
  def transform(s: S, i: I): (S, Chunk[O]) = transform_((s, i))

  /** Chunk form of [[transform]]. */
  def transformAccumulate(s: S, c: Chunk[I]): (S, Chunk[O]) =
    // Same as: c.traverse(i => State(transform(_, i))).map(_.flatten).run(s).value
    c.foldLeft(s -> Chunk.empty[O]) { case ((s, acc), i) =>
      val (s2, os) = transform(s, i)
      (s2, acc ++ os)
    }

  /** Completion function. */
  def onComplete(s: S): Chunk[O] = onComplete_(s)

  /** Converts this scan to a pipe. */
  def toPipe[F[_]]: Pipe[F, I, O] =
    _.pull
      .scanChunks(initial)(transformAccumulate)
      .flatMap(state => Pull.output(onComplete(state)))
      .stream

  /** Steps this scan by a single input, returning a new scan and the output elements computed from the input. */
  def step(i: I): (Scan[S, I, O], Chunk[O]) = {
    val (s, os) = transform(initial, i)
    (new Scan(s, transform_, onComplete_), os)
  }

  /** Composes the supplied scan with this scan.
    *
    * The resulting scan maintains the state of each of the input scans independently.
    */
  def andThen[S2, O2](that: Scan[S2, O, O2]): Scan[(S, S2), I, O2] =
    Scan[(S, S2), I, O2]((initial, that.initial))(
      { case ((s, s2), i) =>
        val (sp, os) = transform(s, i)
        val (s2p, out) = that.transformAccumulate(s2, os)
        ((sp, s2p), out)
      },
      { case (s, s2) =>
        val (s3, out) = that.transformAccumulate(s2, onComplete(s))
        out ++ that.onComplete(s3)
      }
    )

  /** Returns a new scan which transforms output values using the supplied function. */
  def map[O2](f: O => O2): Scan[S, I, O2] =
    new Scan(
      initial,
      transform_.andThen[(S, Chunk[O2])] { case (s, os) => (s, os.map(f)) },
      onComplete_.andThen(_.map(f))
    )

  /** Returns a new scan which transforms input values using the supplied function. */
  def contramap[I2](f: I2 => I): Scan[S, I2, O] =
    new Scan(
      initial,
      AndThen[(S, I2), (S, I)] { case (s, i2) => (s, f(i2)) }.andThen(transform_),
      onComplete_
    )

  def dimap[I2, O2](g: I2 => I)(f: O => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      { (s, i2) =>
        val (s2, os) = transform(s, g(i2))
        (s2, os.map(f))
      },
      onComplete_.andThen(_.map(f))
    )

  /** Transforms the state type. */
  def imapState[S2](g: S => S2)(f: S2 => S): Scan[S2, I, O] =
    Scan[S2, I, O](g(initial))(
      { (s2, i) =>
        val (s3, os) = transform(f(s2), i)
        (g(s3), os)
      },
      AndThen(f).andThen(onComplete_)
    )

  /** Returns a new scan with transformed input and output types.
    *
    * Upon receiving an `I2`, `get` is invoked and the result is fed to the
    * original scan. For each output value, `set` is invoked with the original
    * `I2` input and the computed `O`, yielding a new output of type `O2`.
    */
  def lens[I2, O2](get: I2 => I, set: (I2, O) => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      { (s, i2) =>
        val (s2, os) = transform(s, get(i2))
        (s2, os.map(s => set(i2, s)))
      },
      _ => Chunk.empty
    )

  /** Returns a scan that inputs/outputs pairs of elements, with `I` and `O` in the first element of the pair. */
  def first[A]: Scan[S, (I, A), (O, A)] =
    lens(_._1, (t, o) => (o, t._2))

  /** Returns a scan that inputs/outputs pairs of elements, with `I` and `O` in the second element of the pair. */
  def second[A]: Scan[S, (A, I), (A, O)] =
    lens(_._2, (t, o) => (t._1, o))

  /** Like [[lens]] but some elements are passed to the output (skipping the original scan) while other elements
    * are lensed through the original scan.
    */
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

  /** Like [[semilens]] but the elements of the original scan are output directly. */
  def semipass[I2, O2 >: O](extract: I2 => Either[O2, I]): Scan[S, I2, O2] =
    semilens(extract, (_, o) => o)

  /** Returns a scan that wraps the inputs/outputs with `Either`.
    * Elements on the left pass through the original scan while elements on
    * the right pass through directly.
    */
  def left[A]: Scan[S, Either[I, A], Either[O, A]] =
    semilens(_.fold(i => Right(i), a => Left(Right(a))), (_, o) => Left(o))

  /** Returns a scan that wraps the inputs/outputs with `Either`.
    * Elements on the right pass through the original scan while elements on
    * the left pass through directly.
    */
  def right[A]: Scan[S, Either[A, I], Either[A, O]] =
    semilens(_.fold(a => Left(Left(a)), i => Right(i)), (_, o) => Right(o))

  /** Combines this scan with the supplied scan such that elements on the left
    * are fed through this scan while elements on the right are fed through the
    * suppplied scan. The outputs are joined together.
    */
  def choice[S2, I2, O2 >: O](that: Scan[S2, I2, O2]): Scan[(S, S2), Either[I, I2], O2] =
    Scan[(S, S2), Either[I, I2], O2]((initial, that.initial))(
      { case ((s, s2), e) =>
        e match {
          case Left(i) =>
            val (sp, os) = transform(s, i)
            ((sp, s2), os)
          case Right(i2) =>
            val (s2p, o2s) = that.transform(s2, i2)
            ((s, s2p), o2s)
        }
      },
      { case (s, s2) => onComplete(s) ++ that.onComplete(s2) }
    )

  /** Like [[choice]] but the output elements are kept separate. */
  def choose[S2, I2, O2](t: Scan[S2, I2, O2]): Scan[(S, S2), Either[I, I2], Either[O, O2]] =
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

  implicit def functor[S, I]: Functor[Scan[S, I, *]] =
    new Functor[Scan[S, I, *]] {
      def map[O, O2](s: Scan[S, I, O])(f: O => O2) = s.map(f)
    }

  implicit def contravariant[S, O]: Contravariant[Scan[S, *, O]] =
    new Contravariant[Scan[S, *, O]] {
      def contramap[I, I2](s: Scan[S, I, O])(f: I2 => I) = s.contramap(f)
    }

  implicit def strong[S]: Strong[Scan[S, *, *]] = new Strong[Scan[S, *, *]] {
    def first[A, B, C](fa: Scan[S, A, B]): Scan[S, (A, C), (B, C)] = fa.first
    def second[A, B, C](fa: Scan[S, A, B]): Scan[S, (C, A), (C, B)] = fa.second
    def dimap[A, B, C, D](fab: Scan[S, A, B])(f: C => A)(g: B => D): Scan[S, C, D] =
      fab.dimap(f)(g)
  }
}
