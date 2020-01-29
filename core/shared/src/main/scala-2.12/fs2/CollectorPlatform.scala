package fs2

import scala.collection.generic.{GenericTraversableTemplate, MapFactory, SetFactory, TraversableFactory}
import scala.collection.{MapLike, SetLike, Traversable}

import fs2.internal._
import scala.collection.generic.GenericSetTemplate

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

  implicit def supportsSetFactory[A, C[x] <: Set[x] with SetLike[x, C[x]]](f: SetFactory[C]): Collector.Aux[A, C[A]] =
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
