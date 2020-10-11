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

package fs2

import scala.collection.immutable.ArraySeq
import scala.collection.{Factory, IterableFactory, MapFactory}
import scala.reflect.ClassTag

private[fs2] trait CollectorPlatform { self: Collector.type =>
  implicit def supportsFactory[A, C[_], B](
      f: Factory[A, C[B]]
  ): Collector.Aux[A, C[B]] = make(Builder.fromFactory(f))

  implicit def supportsIterableFactory[A, C[_]](f: IterableFactory[C]): Collector.Aux[A, C[A]] =
    make(Builder.fromIterableFactory(f))

  implicit def supportsMapFactory[K, V, C[_, _]](f: MapFactory[C]): Collector.Aux[(K, V), C[K, V]] =
    make(Builder.fromMapFactory(f))

  /** Use `ArraySeq.untagged` to build a `Collector` where a `ClassTag` is not available.
    */
  implicit def supportsTaggedArraySeq[A: ClassTag](
      a: ArraySeq.type
  ): Collector.Aux[A, ArraySeq[A]] = {
    val _ = a
    make(implicitly[ClassTag[A]] match {
      case ClassTag.Byte =>
        Builder.byteArray.mapResult(ArraySeq.unsafeWrapArray).asInstanceOf[Builder[A, ArraySeq[A]]]
      case _ => Builder.taggedArraySeq[A]
    })
  }

  private[fs2] trait BuilderPlatform { self: Collector.Builder.type =>
    def fromFactory[A, C[_], B](f: Factory[A, C[B]]): Builder[A, C[B]] =
      fromBuilder(f.newBuilder)

    def fromIterableFactory[A, C[_]](f: IterableFactory[C]): Builder[A, C[A]] =
      fromBuilder(f.newBuilder)

    def fromMapFactory[K, V, C[_, _]](f: MapFactory[C]): Builder[(K, V), C[K, V]] =
      fromBuilder(f.newBuilder)

    def taggedArraySeq[A: ClassTag]: Builder[A, ArraySeq[A]] =
      array[A].mapResult(ArraySeq.unsafeWrapArray)
  }
}
