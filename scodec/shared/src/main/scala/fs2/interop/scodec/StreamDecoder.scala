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
package interop
package scodec

import _root_.scodec.{Attempt, DecodeResult, Decoder, Err, codecs}
import _root_.scodec.bits.BitVector

import cats.MonadThrow

/** Supports binary decoding of a stream that emits elements as they are decoded.
  *
  * The main purpose of using a `StreamDecoder` over a `scodec.Decoder` is mixing decoding with
  * processing. For example, `scodec.codecs.vector(decoderA): Decoder[Vector[A]]` could be used to
  * decode a bit stream but the decoded `Vector[A]` would not be emitted until the end of the bit
  * stream. With `StreamDecoder.many(decoderA): StreamDecoder[A]`, each decoded `A` value is emitted
  * as soon as it is decoded.
  *
  * The `StreamDecoder` companion has various constructors -- most importantly, `once` and `many`,
  * that allow a `Decoder[A]` to be lifted to a `StreamDecoder[A]`.
  *
  * Given a `StreamDecoder[A]`, a bit stream can be decoded via the `decode` method or by calling a
  * variant of `toPipe`.
  */
final class StreamDecoder[+A] private (private val step: StreamDecoder.Step[A]) { self =>

  import StreamDecoder._

  /** Converts this decoder to a `Pipe[F, BitVector, A]`. */
  def toPipe[F[_]: RaiseThrowable]: Pipe[F, BitVector, A] = decode(_)

  /** Converts this decoder to a `Pipe[F, Byte, A]`. */
  def toPipeByte[F[_]: RaiseThrowable]: Pipe[F, Byte, A] =
    in => in.chunks.map(_.toBitVector).through(toPipe)

  /** Returns a `Stream[F, A]` given a `Stream[F, BitVector]`. */
  def decode[F[_]: RaiseThrowable](s: Stream[F, BitVector]): Stream[F, A] =
    apply(s).void.stream

  /** Returns a `Pull[F, A, Option[Stream[F, BitVector]]]` given a `Stream[F, BitVector]`. The
    * result of the returned pull is the remainder of the input stream that was not used in
    * decoding.
    */
  def apply[F[_]: RaiseThrowable](
      s: Stream[F, BitVector]
  ): Pull[F, A, Option[Stream[F, BitVector]]] =
    step match {
      case Empty         => Pull.pure(Some(s))
      case Result(a)     => Pull.output1(a).as(Some(s))
      case Failed(cause) => Pull.raiseError(cause)
      case Append(x, y) =>
        x(s).flatMap {
          case None      => Pull.pure(None)
          case Some(rem) => y()(rem)
        }

      case Decode(decoder, once, failOnErr) =>
        def loop(
            carry: BitVector,
            s: Stream[F, BitVector]
        ): Pull[F, A, Option[Stream[F, BitVector]]] =
          s.pull.uncons1.flatMap {
            case Some((hd, tl)) =>
              val buffer = carry ++ hd
              decoder(buffer) match {
                case Attempt.Successful(DecodeResult(value, remainder)) =>
                  val next = if (remainder.isEmpty) tl else tl.cons1(remainder)
                  val p = value(next)
                  if (once) p
                  else
                    p.flatMap {
                      case Some(next) => loop(BitVector.empty, next)
                      case None       => Pull.pure(None)
                    }
                case Attempt.Failure(_: Err.InsufficientBits) =>
                  loop(buffer, tl)
                case Attempt.Failure(comp: Err.Composite)
                    if comp.errs.exists(_.isInstanceOf[Err.InsufficientBits]) =>
                  loop(buffer, tl)
                case Attempt.Failure(e) =>
                  if (failOnErr) Pull.raiseError(CodecError(e))
                  else Pull.pure(Some(tl.cons1(buffer)))
              }
            case None => if (carry.isEmpty) Pull.pure(None) else Pull.pure(Some(Stream(carry)))
          }
        loop(BitVector.empty, s)

      case Isolate(bits, decoder) =>
        def loop(
            carry: BitVector,
            s: Stream[F, BitVector]
        ): Pull[F, A, Option[Stream[F, BitVector]]] =
          s.pull.uncons1.flatMap {
            case Some((hd, tl)) =>
              val (buffer, remainder) = (carry ++ hd).splitAt(bits)
              if (buffer.size == bits)
                decoder[F](Stream(buffer)) >> Pull.pure(Some(tl.cons1(remainder)))
              else loop(buffer, tl)
            case None => if (carry.isEmpty) Pull.pure(None) else Pull.pure(Some(Stream(carry)))
          }
        loop(BitVector.empty, s)
    }

  /** Creates a stream decoder that, upon decoding an `A`, applies it to the supplied function and
    * decodes the next part of the input with the returned decoder. When that decoder finishes, the
    * remainder of the input is returned to the original decoder for further decoding.
    */
  def flatMap[B](f: A => StreamDecoder[B]): StreamDecoder[B] =
    new StreamDecoder[B](
      self.step match {
        case Empty         => Empty
        case Result(a)     => f(a).step
        case Failed(cause) => Failed(cause)
        case Decode(g, once, failOnErr) =>
          Decode(in => g(in).map(_.map(_.flatMap(f))), once, failOnErr)
        case Isolate(bits, decoder) => Isolate(bits, decoder.flatMap(f))
        case Append(x, y)           => Append(x.flatMap(f), () => y().flatMap(f))
      }
    )

  def handleErrorWith[A2 >: A](f: Throwable => StreamDecoder[A2]): StreamDecoder[A2] =
    new StreamDecoder[A2](
      self.step match {
        case Empty         => Empty
        case Result(a)     => Result(a)
        case Failed(cause) => f(cause).step
        case Decode(g, once, failOnErr) =>
          Decode(in => g(in).map(_.map(_.handleErrorWith(f))), once, failOnErr)
        case Isolate(bits, decoder) => Isolate(bits, decoder.handleErrorWith(f))
        case Append(x, y)           => Append(x.handleErrorWith(f), () => y().handleErrorWith(f))
      }
    )

  /** Maps the supplied function over each output of this decoder. */
  def map[B](f: A => B): StreamDecoder[B] = flatMap(a => StreamDecoder.emit(f(a)))

  /** Creates a stream decoder that first decodes until this decoder finishes and then decodes using
    * the supplied decoder.
    *
    * Note: this should not be used to write recursive decoders (e.g., `def ints: StreamDecoder[A] =
    * once(int32) ++ ints`) if each incremental decoding step can fail with `InsufficientBits`.
    * Otherwise, it decoding can get stuck in an infinite loop, where the remaining bits are fed to
    * the recursive call.
    */
  def ++[A2 >: A](that: => StreamDecoder[A2]): StreamDecoder[A2] =
    new StreamDecoder(Append(this, () => that))

  /** Alias for `StreamDecoder.isolate(bits)(this)`. */
  def isolate(bits: Long): StreamDecoder[A] = StreamDecoder.isolate(bits)(this)

  /** Converts this stream decoder to a `Decoder[Vector[A]]`. */
  def strict: Decoder[Vector[A]] =
    new Decoder[Vector[A]] {
      def decode(bits: BitVector): Attempt[DecodeResult[Vector[A]]] = {
        type ET[X] = Either[Throwable, X]
        self
          .map(Left(_))
          .apply[Fallible](Stream(bits))
          .flatMap { remainder =>
            remainder
              .map { r =>
                r.map(Right(_)).pull.echo
              }
              .getOrElse(Pull.done)
          }
          .stream
          .compile[Fallible, ET, Either[A, BitVector]]
          .fold((Vector.empty[A], BitVector.empty)) { case ((acc, rem), entry) =>
            entry match {
              case Left(a)   => (acc :+ a, rem)
              case Right(r2) => (acc, rem ++ r2)
            }
          }
          .fold(
            {
              case CodecError(e) => Attempt.failure(e)
              case other         => Attempt.failure(Err.General(other.getMessage, Nil))
            },
            { case (acc, rem) => Attempt.successful(DecodeResult(acc, rem)) }
          )
      }
    }
}

object StreamDecoder {
  private sealed trait Step[+A]
  private case object Empty extends Step[Nothing]
  private case class Result[A](value: A) extends Step[A]
  private case class Failed(cause: Throwable) extends Step[Nothing]
  private case class Decode[A](
      f: BitVector => Attempt[DecodeResult[StreamDecoder[A]]],
      once: Boolean,
      failOnErr: Boolean
  ) extends Step[A]
  private case class Isolate[A](bits: Long, decoder: StreamDecoder[A]) extends Step[A]
  private case class Append[A](x: StreamDecoder[A], y: () => StreamDecoder[A]) extends Step[A]

  /** Stream decoder that emits no elements. */
  val empty: StreamDecoder[Nothing] = new StreamDecoder[Nothing](Empty)

  /** Stream decoder that emits a single `A` and consumes no bits from the input. */
  def emit[A](a: A): StreamDecoder[A] = new StreamDecoder[A](Result(a))

  /** Stream decoder that emits the supplied `A` values and consumes no bits from the input. */
  def emits[A](as: Iterable[A]): StreamDecoder[A] =
    as.foldLeft(empty: StreamDecoder[A])((acc, a) => acc ++ emit(a))

  /** Creates a stream decoder that decodes one `A` using the supplied decoder. Input bits are
    * buffered until the decoder is able to decode an `A`.
    */
  def once[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](
      Decode(in => decoder.decode(in).map(_.map(emit)), once = true, failOnErr = true)
    )

  /** Creates a stream decoder that repeatedly decodes `A` values using the supplied decoder.
    */
  def many[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](
      Decode(in => decoder.decode(in).map(_.map(emit)), once = false, failOnErr = true)
    )

  /** Creates a stream decoder that attempts to decode one `A` using the supplied decoder. Input
    * bits are buffered until the decoder is able to decode an `A`. If decoding fails, the bits are
    * not consumed and the stream decoder yields no values.
    */
  def tryOnce[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](
      Decode(in => decoder.decode(in).map(_.map(emit)), once = true, failOnErr = false)
    )

  /** Creates a stream decoder that repeatedly decodes `A` values until decoding fails. If decoding
    * fails, the read bits are not consumed and the stream decoder terminates, having emitted any
    * successfully decoded values earlier.
    */
  def tryMany[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](
      Decode(in => decoder.decode(in).map(_.map(emit)), once = false, failOnErr = false)
    )

  /** Creates a stream decoder that fails decoding with the specified exception. */
  def raiseError(cause: Throwable): StreamDecoder[Nothing] = new StreamDecoder(Failed(cause))

  /** Creates a stream decoder that fails decoding with the specified error. */
  def raiseError(err: Err): StreamDecoder[Nothing] = raiseError(CodecError(err))

  /** Creates a stream decoder that reads the specified number of bits and then decodes them with
    * the supplied stream decoder. Any remainder from the inner stream decoder is discarded.
    */
  def isolate[A](bits: Long)(decoder: StreamDecoder[A]): StreamDecoder[A] =
    new StreamDecoder(Isolate(bits, decoder))

  /** Creates a stream decoder that ignores the specified number of bits. */
  def ignore(bits: Long): StreamDecoder[Nothing] =
    once(codecs.ignore(bits)).flatMap(_ => empty)

  implicit val instance: MonadThrow[StreamDecoder] = new MonadThrow[StreamDecoder] {
    def pure[A](a: A) = StreamDecoder.emit(a)
    def flatMap[A, B](da: StreamDecoder[A])(f: A => StreamDecoder[B]) = da.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => StreamDecoder[Either[A, B]]): StreamDecoder[B] =
      f(a).flatMap {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
    def handleErrorWith[A](da: StreamDecoder[A])(
        f: Throwable => StreamDecoder[A]
    ): StreamDecoder[A] =
      da.handleErrorWith(f)
    def raiseError[A](e: Throwable): StreamDecoder[A] =
      StreamDecoder.raiseError(e)
  }
}
