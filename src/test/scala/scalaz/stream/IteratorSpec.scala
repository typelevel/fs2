package scalaz.stream

import org.scalacheck._
import Prop._

/**
 * io.iterator tests
 */
class IteratorSpec extends Properties("iterators") {

  property("io.iterator completes immediately from an empty iterator") = secure {
    io.iterator[Int](Iterator.empty).runLog.run.isEmpty
  }

  property("io.iterator uses all its values and completes") = forAll { (ints: Vector[Int]) =>
    io.iterator[Int](ints.toIterator).runLog.run == ints
  }

  property("io.iterator is re-usable") = forAll { (ints: Vector[Int]) =>
    io.iterator(ints.toIterator).runLog.run == io.iterator(ints.toIterator).runLog.run
  }

}
