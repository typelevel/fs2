package fs2.io

import scala.collection.JavaConverters._

private[fs2] object CollectionCompat {
  implicit class JIteratorOps[A](private val self: java.util.Iterator[A]) extends AnyVal {
    def asScala: Iterator[A] = asScalaIterator(self)
  }
  implicit class JIterableOps[A](private val self: java.lang.Iterable[A]) extends AnyVal {
    def asScala: Iterable[A] = iterableAsScalaIterable(self)
  }
  implicit class ListOps[A](private val self: List[A]) extends AnyVal {
    def asJava: java.util.List[A] = seqAsJavaList(self)
  }
}