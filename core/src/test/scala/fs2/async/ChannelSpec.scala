package fs2
package async

import TestUtil._
import fs2.util.Task
// import fs2.Stream.Handle
import org.scalacheck.Prop._
import org.scalacheck._
// import java.util.concurrent.atomic.AtomicLong

object ChannelSpec extends Properties("async.channel") {

  //property("observe") = forAll { (s: PureStream[Int]) =>
  //  println(run { s.get })
  //  val sum = new AtomicLong(0)
  //  val out = run {
  //    channel.observeAsync(10)(s.get.covary[Task]) {
  //      _.evalMap(i => Task.delay { sum.addAndGet(i.toLong); () })
  //    }}
  //  out.map(_.toLong).sum ?= sum.get
  //}

  def trace[F[_],A](msg: String)(s: Stream[F,A]) = s mapChunks { a => println(msg + ": " + a.toList); a }

  property("sanity-test") = protect { // (s: PureStream[Int]) =>
    val s = Stream.constant(1)
    val s2 = s.covary[Task].flatMap { i => Stream.emit(i).onFinalize(Task.delay { println(s"finalizing $i")}) }
    val q = async.unboundedQueue[Task,Int].run
    // q.enqueue1(0).run
    // run { s2 }
    run { (trace("s2")(s2) merge trace("q")(q.dequeue)).take(10) }
    true
  }
}
