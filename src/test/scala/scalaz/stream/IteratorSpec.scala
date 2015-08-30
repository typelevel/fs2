package scalaz.stream

import org.scalacheck._
import Prop._
import scalaz.concurrent.Task

/**
 * io.iterator tests
 */
class IteratorSpec extends Properties("iterators") {

  property("io.iterator completes immediately from an empty iterator") = secure {
    io.iterator[Int](Task(Iterator.empty)).runLog.run.isEmpty
  }

  property("io.iterator uses all its values and completes") = forAll { (ints: Vector[Int]) =>
    val iterator = Task(ints.toIterator)
    io.iterator[Int](iterator).runLog.run == ints
  }

  property("io.iterator is re-usable") = forAll { (ints: Vector[Int]) =>
    val iterator = Task(ints.toIterator)
    io.iterator(iterator).runLog.run == io.iterator(iterator).runLog.run
  }

}
