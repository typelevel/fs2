package scalaz.stream

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task

class IteratorSpec extends Properties("iterators") {

  case class IteratorResource(var cleaned: Boolean = false) {
    def clean() = {
      cleaned = true
    }
  }

  def acquire[T](resource: IteratorResource, items: T*): Task[(IteratorResource, Iterator[T])] = {
    Task((resource, items.toIterator))
  }

  //For use with io.iterators
  def acquires[T](resource: IteratorResource, items: Seq[Seq[T]]): Task[(IteratorResource, Process0[Iterator[T]])] = {
    Task((resource, Process.emitAll(items.map(_.toIterator))))
  }

  def release(toRelease: IteratorResource): Task[Unit] = {
    Task(toRelease.clean())
  }

  property("Process.iterator completes immediately from an empty iterator") = secure {
    Process.iterator[Int](Task(Iterator.empty)).runLog.run.isEmpty
  }

  property("Process.iterator uses all its values and completes") = forAll { (ints: Vector[Int]) =>
    val iterator = Task(ints.toIterator)
    Process.iterator[Int](iterator).runLog.run == ints
  }

  property("Process.iterator is re-usable") = forAll { (ints: List[Int]) =>
    val iterator = Task(ints.toIterator)
    Process.iterator(iterator).runLog.run == Process.iterator(iterator).runLog.run
  }

  property("io.iterator cleans its resource") = secure {
    val resource = IteratorResource()

    io.iterator(acquire(resource))(release).run.run

    resource.cleaned
  }

  property("io.iterators emits all the values") = forAll { (ints: Seq[Seq[Int]]) =>
    val resource = IteratorResource()

    io.iterators(acquires(resource, ints))(release).flatMap(identity).runLog.run == ints.flatten &&
    resource.cleaned
  }

}
