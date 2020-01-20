package fs2.io

import scala.collection.JavaConverters._

private[fs2] object CollectionCompat {
  implicit class JIteratorOps[A](private val self: java.util.Iterator[A]) extends AnyVal {
    def asScala: Iterator[A] = asScalaIterator(self)
  }
  implicit class JIterableOps[A](private val self: java.lang.Iterable[A]) extends AnyVal {
    def asScala: Iterable[A] = iterableAsScalaIterable(self)
  }
  implicit class JSetOps[A](private val self: java.util.Set[A]) extends AnyVal {
    def asScala: Set[A] = asScalaSet(self).toSet
  }
  implicit class ListOps[A](private val self: List[A]) extends AnyVal {
    def asJava: java.util.List[A] = seqAsJavaList(self)
  }
  implicit class SetOps[A](private val self: Set[A]) extends AnyVal {
    def asJava: java.util.Set[A] = setAsJavaSet(self)
  }
  implicit class EnumerationOps[A](private val self: java.util.Enumeration[A]) extends AnyVal {
    def asScala: Iterator[A] = enumerationAsScalaIterator(self)
  }
}
