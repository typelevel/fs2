package scalaz.stream

import org.scalacheck._
import Prop._
import scalaz.concurrent.Task

class IteratorSpec extends Properties("iterators") {

  property("Process.iterator completes immediately from an empty iterator") = secure {
    Process.iterator[Int](Task(Iterator.empty)).runLog.run.isEmpty
  }

  property("Process.iterator uses all its values and completes") = forAll { (ints: Vector[Int]) =>
    val iterator = Task(ints.toIterator)
    Process.iterator[Int](iterator).runLog.run == ints
  }

  property("Process.iterator is re-usable") = forAll { (ints: Vector[Int]) =>
    val iterator = Task(ints.toIterator)
    Process.iterator(iterator).runLog.run == Process.iterator(iterator).runLog.run
  }

  //io.iterator tests

  case class IteratorResource[T](items: T*) {
    private var released: Boolean = false

    def release(): Unit = {
      released = true
    }

    def isReleased: Boolean = released

    def iterator: Iterator[T] = items.iterator
  }

  property("io.iterator releases its resource") = forAll { (ints: Vector[Int]) =>
    var isReleased: Boolean = false

    def acquire(): Task[IteratorResource[Int]] = {
      Task.delay {
        IteratorResource(ints: _*)
      }
    }

    def release(resource: IteratorResource[_]): Task[Unit] = {
      Task.delay {
        resource.release()
        isReleased = resource.isReleased
      }
    }

    def createIterator(resource: IteratorResource[Int]): Task[Iterator[Int]] = {
      Task.delay {
        resource.iterator
      }
    }

    io.iterator(acquire())(createIterator)(release).run.run

    isReleased
  }

}
