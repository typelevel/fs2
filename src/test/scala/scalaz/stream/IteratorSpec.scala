package scalaz.stream

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task

/**
 * io.iterator tests
 */
class IteratorSpec extends Properties("iterators") {

  property("io.iterate completes immediately from an empty iterator") = secure {
    io.iterate[Int](Iterator.empty).runLog.run.isEmpty
  }

  property("io.iterate uses all its values and completes") = forAll { (ints: Vector[Int]) =>
    io.iterate[Int](ints.toIterator).runLog.run == ints
  }

  property("io.iterate is re-usable") = forAll { (ints: Vector[Int]) =>
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

    def acquire: Task[IteratorResource[Int]] =
      Task.delay{
       resource = Some(IteratorResource(ints: _*))
       resource.get
     }

    def release(resource: IteratorResource[_]): Task[Unit] =
      Task.delay { resource.release() }



    io.iterateR(acquire)(release)(_.iterator).run.run

    resource.exists(_.isReleased)
  }

}
