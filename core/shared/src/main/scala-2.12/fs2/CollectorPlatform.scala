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

import scala.collection.generic.{
  GenericTraversableTemplate,
  MapFactory,
  SetFactory,
  TraversableFactory
}
import scala.collection.{MapLike, SetLike, Traversable}

import fs2.internal._

private[fs2] trait CollectorPlatform { self: Collector.type =>
  implicit def supportsFactory[A, C[_], B](
      f: Factory[A, C[B]]
  ): Collector.Aux[A, C[B]] = make(Builder.fromFactory(f))

  implicit def supportsTraversableFactory[A, C[x] <: Traversable[x] with GenericTraversableTemplate[
    x,
    C
  ]](
      f: TraversableFactory[C]
  ): Collector.Aux[A, C[A]] = make(Builder.fromTraversableFactory(f))

  implicit def supportsMapFactory[K, V, C[a, b] <: collection.Map[a, b] with MapLike[
    a,
    b,
    C[a, b]
  ]](
      f: MapFactory[C]
  ): Collector.Aux[(K, V), C[K, V]] =
    make(Builder.fromMapFactory(f))

  implicit def supportsSetFactory[A, C[x] <: Set[x] with SetLike[x, C[x]]](
      f: SetFactory[C]
  ): Collector.Aux[A, C[A]] =
    make(Builder.fromSetFactory(f))

  private[fs2] trait BuilderPlatform { self: Collector.Builder.type =>
    def fromFactory[A, C[_], B](f: Factory[A, C[B]]): Builder[A, C[B]] =
      fromBuilder(f())

    def fromTraversableFactory[A, C[x] <: Traversable[x] with GenericTraversableTemplate[x, C]](
        f: TraversableFactory[C]
    ): Builder[A, C[A]] =
      fromBuilder(f.newBuilder[A])

    def fromMapFactory[K, V, C[a, b] <: collection.Map[a, b] with MapLike[a, b, C[a, b]]](
        f: MapFactory[C]
    ): Builder[(K, V), C[K, V]] =
      fromBuilder(f.newBuilder)

    def fromSetFactory[A, C[x] <: collection.Set[x] with SetLike[x, C[x]]](
        f: SetFactory[C]
    ): Builder[A, C[A]] =
      fromBuilder(f.newBuilder)
  }
}
