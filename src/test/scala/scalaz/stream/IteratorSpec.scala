package scalaz.stream

import org.scalacheck._
import Prop._

/**
 * io.iterator tests
 */
class IteratorSpec extends Properties("iterators") {

  property("io.iterator completes immediately from an empty iterator") = secure {
    io.iterate[Int](Iterator.empty).runLog.run.isEmpty
  }

  property("io.iterator uses all its values and completes") = forAll { (ints: Vector[Int]) =>
    io.iterate[Int](ints.toIterator).runLog.run == ints
  }

  property("io.iterator is re-usable") = forAll { (ints: Vector[Int]) =>
    io.iterate(ints.toIterator).runLog.run == io.iterate(ints.toIterator).runLog.run
  }

  case class IteratorResource[T](items: T*) {
    private var released: Boolean = false

    def release(): Unit = {
      released = true
    }

    def isReleased: Boolean = released

    def iterator: Iterator[T] = items.iterator
  }

  property("io.iterator releases its resource") = forAll { (ints: Vector[Int]) =>
    var resource: Option[IteratorResource[Int]] = None

    def acquire: IteratorResource[Int] = {
      resource = Some(IteratorResource(ints: _*))
      resource.get
    }

    def release(resource: IteratorResource[_]): Unit = {
      resource.release()
    }

    def rcv(resource: IteratorResource[Int]): Iterator[Int] = {
      resource.iterator
    }

    io.iterator(acquire)(release)(rcv).run.run

    resource.exists(_.isReleased)
  }

}
