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

import java.util.concurrent.Flow.Processor
import cats.effect.Async

package object fs2 {

  /** A stream transformation represented as a function from stream to stream.
    *
    * Pipes are typically applied with the `through` operation on `Stream`.
    */
  type Pipe[F[_], -I, +O] = Stream[F, I] => Stream[F, O]

  object Pipe {
    final class FromProcessorPartiallyApplied[F[_]](private val dummy: Boolean) extends AnyVal {
      def apply[I, O](
          processor: Processor[I, O],
          chunkSize: Int
      )(implicit
          F: Async[F]
      ): Pipe[F, I, O] =
        new interop.flow.ProcessorPipe(processor, chunkSize)
    }

    /** Creates a [[Pipe]] from the given [[Processor]].
      *
      * The input stream won't be consumed until you request elements from the output stream,
      * and thus the processor is not initiated until then.
      *
      * @note The [[Pipe]] can be reused multiple times as long as the [[Processor]] can be reused.
      * Each invocation of the pipe will create and manage its own internal [[Publisher]] and [[Subscriber]],
      * and use them to subscribe to and from the [[Processor]] respectively.
      *
      * @param [[processor]] the [[Processor]] that represents the [[Pipe]] logic.
      * @param chunkSize setup the number of elements asked each time from the upstream [[Publisher]].
      *                  A high number may be useful if the publisher is triggering from IO,
      *                  like requesting elements from a database.
      *                  A high number will also lead to more elements in memory.
      */
    def fromProcessor[F[_]]: FromProcessorPartiallyApplied[F] =
      new FromProcessorPartiallyApplied[F](dummy = true)
  }

  /** A stream transformation that combines two streams in to a single stream,
    * represented as a function from two streams to a single stream.
    *
    * `Pipe2`s are typically applied with the `through2` operation on `Stream`.
    */
  type Pipe2[F[_], -I, -I2, +O] = (Stream[F, I], Stream[F, I2]) => Stream[F, O]

  /** Indicates that a stream evaluates no effects.
    *
    * Because [[Stream]] is covariant, A `Stream[Pure,O]` is also an instance of `Stream[F,O]` for all `F`.
    *
    * This should not be confused with [[cats.Id]], which provides an alternative encoding of pure streams,
    * namely `Stream[Id, O]`. The difference is that `Stream[Id, O]` achieves purity by using an effect type
    * whose evaluation is a no-op, whereas `Stream[Pure, O]` achieves purity by using an effect type that
    * has no instances and therefore cannot be instantiated in the first place.
    */
  type Pure[A] <: Nothing

  /** Alias for `Nothing` which works better with type inference.
    */
  @deprecated("Use Nothing instead", "3.3.0")
  type INothing <: Nothing
}
