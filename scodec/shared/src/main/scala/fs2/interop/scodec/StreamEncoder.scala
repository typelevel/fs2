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

// Adapted from scodec-stream, licensed under 3-clause BSD

package fs2
package interop
package scodec

import _root_.scodec.bits.BitVector
import _root_.scodec.{Encoder, Err}

import cats.Invariant

/** A streaming encoding process, represented as a `Stream[Pure, A] => Pull[Pure, BitVector,
  * Option[(Stream[Pure, A], StreamEncoder[A])]]`.
  */
final class StreamEncoder[A] private (private val step: StreamEncoder.Step[A]) { self =>

  /** Encode the given sequence of `A` values to a `BitVector`, raising an exception in the event of
    * an encoding error.
    */
  def encodeAllValid(in: Seq[A]): BitVector = {
    type ET[X] = Either[Throwable, X]
    encode[Fallible](Stream.emits(in))
      .compile[Fallible, ET, BitVector]
      .fold(BitVector.empty)(_ ++ _)
      .fold(e => throw e, identity)
  }

  /** Converts this encoder to a `Pipe[F, A, BitVector]`. */
  def toPipe[F[_]: RaiseThrowable]: Pipe[F, A, BitVector] =
    in => encode(in)

  /** Converts this encoder to a `Pipe[F, A, Byte]`. */
  def toPipeByte[F[_]: RaiseThrowable]: Pipe[F, A, Byte] =
    in => encode(in).flatMap(bits => Stream.chunk(Chunk.byteVector(bits.bytes)))

  /** Encodes the supplied stream of `A` values in to a stream of `BitVector`. */
  def encode[F[_]: RaiseThrowable](in: Stream[F, A]): Stream[F, BitVector] =
    apply(in).void.stream

  private def apply[F[_]: RaiseThrowable](
      in: Stream[F, A]
  ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] = {
    def loop(
        s: Stream[F, A],
        encoder: StreamEncoder[A]
    ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
      encoder.step[F](s).flatMap {
        case Some((s, next)) => loop(s, next)
        case None            => Pull.pure(None)
      }
    loop(in, this)
  }

  private def or(other: StreamEncoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](
      new StreamEncoder.Step[A] {
        def apply[F[_]: RaiseThrowable](
            s: Stream[F, A]
        ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
          self.step(s).flatMap {
            case Some(x) => Pull.pure(Some(x))
            case None    => other.step(s)
          }
      }
    )

  /** Creates a stream encoder that first encodes with this encoder and then when complete, encodes
    * the remainder with the supplied encoder.
    */
  def ++(that: => StreamEncoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](
      new StreamEncoder.Step[A] {
        def apply[F[_]: RaiseThrowable](
            s: Stream[F, A]
        ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
          self.step(s).map(_.map { case (s1, next) => (s1, next.or(that)) })
      }
    )

  /** Encodes values as long as there are more inputs. */
  def repeat: StreamEncoder[A] = this ++ repeat

  /** Transform the input type of this `StreamEncoder`. */
  def xmapc[B](f: A => B)(g: B => A): StreamEncoder[B] =
    new StreamEncoder[B](
      new StreamEncoder.Step[B] {
        def apply[F[_]: RaiseThrowable](
            s: Stream[F, B]
        ): Pull[F, BitVector, Option[(Stream[F, B], StreamEncoder[B])]] =
          self.step(s.map(g)).map(_.map { case (s1, e1) => s1.map(f) -> e1.xmapc(f)(g) })
      }
    )
}

object StreamEncoder {

  private trait Step[A] {
    def apply[F[_]: RaiseThrowable](
        s: Stream[F, A]
    ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]]
  }

  /** Creates a stream encoder that consumes no values and emits no bits. */
  def empty[A]: StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        Pull.pure(None)
    })

  /** Creates a stream encoder that encodes a single value of input using the supplied encoder. */
  def once[A](encoder: Encoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        s.pull.uncons1.flatMap {
          case None => Pull.pure(None)
          case Some((a, s1)) =>
            encoder
              .encode(a)
              .fold(
                e => Pull.raiseError(CodecError(e)),
                b => Pull.output1(b)
              ) >> Pull.pure(Some(s1 -> empty))
        }
    })

  /** Creates a stream encoder that encodes all input values using the supplied encoder. */
  def many[A](encoder: Encoder[A]): StreamEncoder[A] = once(encoder).repeat

  /** Creates a stream encoder which encodes a single value, then halts. Unlike `once`, if an
    * encoding failure occurs, the resulting stream is not terminated.
    */
  def tryOnce[A](encoder: Encoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        s.pull.uncons1.flatMap {
          case None => Pull.pure(None)
          case Some((a, s1)) =>
            encoder
              .encode(a)
              .fold(
                _ => Pull.pure(Some(s1.cons1(a) -> empty)),
                b => Pull.output1(b) >> Pull.pure(Some(s1 -> empty))
              )
        }
    })

  /** Creates a stream encoder which encodes all input values, then halts. Unlike `many`, if an
    * encoding failure occurs, the resulting stream is not terminated.
    */
  def tryMany[A](encoder: Encoder[A]): StreamEncoder[A] = tryOnce(encoder).repeat

  /** Creates a stream encoder that emits the given `BitVector`, then halts. */
  def emit[A](bits: BitVector): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        Pull.output1(bits) >> Pull.pure(Some(s -> empty[A]))
    })

  /** The encoder that consumes no input and halts with the given error. */
  def raiseError[A](err: Throwable): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        Pull.raiseError(err)
    })

  /** The encoder that consumes no input and halts with the given error message. */
  def raiseError[A](err: Err): StreamEncoder[A] = raiseError(CodecError(err))

  implicit val instance: Invariant[StreamEncoder] = new Invariant[StreamEncoder] {
    def imap[A, B](fa: StreamEncoder[A])(f: A => B)(g: B => A): StreamEncoder[B] =
      fa.xmapc(f)(g)
  }
}
