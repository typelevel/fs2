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
    val s = Stream.range(0,100)
    val s2 = s.covary[Task].flatMap { i => Stream.emit(i).onFinalize(Task.delay { println(s"finalizing $i")}) }
    val q = async.unboundedQueue[Task,Int].run
    // q.enqueue1(0).run
    // run { s2 }
    run { merge2(trace("s2")(s2), trace("q")(q.dequeue)).take(10) }
    // ( (trace("s2")(s2) merge trace("q")(q.dequeue)).take(10) ).runTrace(Trace.Off).run.run
    true
  }

  import Async.Future
  def merge2[F[_]:Async,A](a: Stream[F,A], a2: Stream[F,A]): Stream[F,A] = {
    type FS = Future[F,Stream[F,A]] // Option[Step[Chunk[A],Stream[F,A]]]]
    def go(fa: FS, fa2: FS): Stream[F,A] = (fa race fa2).stream.flatMap {
      case Left(sa) => sa.uncons.flatMap {
        case Some(hd #: sa) => Stream.chunk(hd) ++ (sa.fetchAsync flatMap (go(_,fa2)))
        case None => println("left stream terminated"); fa2.stream.flatMap(identity)
      }
      case Right(sa2) => sa2.uncons.flatMap {
        case Some(hd #: sa2) => Stream.chunk(hd) ++ (sa2.fetchAsync flatMap (go(fa,_)))
        case None => println("right stream terminated"); fa.stream.flatMap(identity)
      }
    }
    a.fetchAsync flatMap { fa =>
    a2.fetchAsync flatMap { fa2 => go(fa, fa2) }}
  }
}
