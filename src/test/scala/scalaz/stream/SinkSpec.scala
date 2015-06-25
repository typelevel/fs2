package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties

import scalaz.concurrent.Task
import scalaz.stream.Process._

class SinkSpec extends Properties("Sink") {
  property("adapt") = forAll { list: List[Int] =>
    val results = scala.collection.mutable.ListBuffer[String]()
    val sink: Sink[Task, String] = Process.constant { a =>
      Task.delay(results += a)
    }
    val process = Process.emitAll(list)
    process.to(sink.adapt(_.toString)).run.run
    results.toList == list.map(_.toString)
  }
}