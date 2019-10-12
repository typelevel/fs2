package fs2

import scala.collection.generic.{GenericTraversableTemplate, MapFactory, TraversableFactory}
import scala.collection.{MapLike, Traversable}

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
  }
}
