package fs2
package async

import fs2.util.Task
import java.util.concurrent.atomic.AtomicLong

class ChannelSpec extends Fs2Spec {

  "Async channels" - {

    "observe/observeAsync" in {
      forAll { (s: PureStream[Int]) =>
        val sum = new AtomicLong(0)
        val out = runLog {
          channel.observe(s.get.covary[Task]) {
            _.evalMap(i => Task.delay { sum.addAndGet(i.toLong); () })
          }
        }
        out.map(_.toLong).sum shouldBe sum.get
        sum.set(0)
        val out2 = runLog {
          channel.observeAsync(s.get.covary[Task], maxQueued = 10) {
            _.evalMap(i => Task.delay { sum.addAndGet(i.toLong); () })
          }
        }
        out2.map(_.toLong).sum shouldBe sum.get
      }
    }

    "sanity-test" in {
      val s = Stream.range(0,100)
      val s2 = s.covary[Task].flatMap { i => Stream.emit(i).onFinalize(Task.delay { println(s"finalizing $i")}) }
      val q = async.unboundedQueue[Task,Int].unsafeRun
      runLog { merge2(trace("s2")(s2), trace("q")(q.dequeue)).take(10) } shouldBe Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    }
  }

  def trace[F[_],A](msg: String)(s: Stream[F,A]) = s mapChunks { a => println(msg + ": " + a.toList); a }

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
