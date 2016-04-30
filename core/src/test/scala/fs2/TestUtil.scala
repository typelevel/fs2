package fs2

import java.util.concurrent.atomic.AtomicInteger

import java.util.concurrent.{ThreadFactory, Executors, ScheduledExecutorService}

import fs2.util.Task
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.duration._

trait TestUtil {

  implicit val S = Strategy.fromFixedDaemonPool(8)
  // implicit val S = Strategy.fromCachedDaemonPool("test-thread-worker")
  implicit val Sch : ScheduledExecutorService = Executors.newScheduledThreadPool(8, new ThreadFactory {
    val idx = new AtomicInteger(0)
    def newThread(r: Runnable): Thread = {
      val t = new Thread(r,s"fs2.spec-default-scheduler-${idx.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
  })

  def runLog[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.unsafeRun
  def runFor[A](timeout: FiniteDuration = 3.seconds)(s: Stream[Task,A]): Vector[A] = s.runLog.run.unsafeRunFor(timeout)

  def throws[A](err: Throwable)(s: Stream[Task,A]): Boolean =
    s.runLog.run.unsafeAttemptRun match {
      case Left(e) if e == err => true
      case _ => false
    }

  implicit def arbChunk[A](implicit A: Arbitrary[A]): Arbitrary[Chunk[A]] = Arbitrary(
    Gen.frequency(
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.indexedSeq(as.toVector)),
      10 -> Gen.listOf(A.arbitrary).map(Chunk.seq),
      5 -> A.arbitrary.map(a => Chunk.singleton(a)),
      1 -> Chunk.empty
    )
  )

  /** Newtype for generating test cases. Use the `tag` for labeling properties. */
  case class PureStream[+A](tag: String, get: Stream[Pure,A])
  implicit def arbPureStream[A:Arbitrary] = Arbitrary(PureStream.gen[A])

  case class SmallPositive(get: Int)
  implicit def arbSmallPositive = Arbitrary(Gen.choose(1,20).map(SmallPositive(_)))

  case class SmallNonnegative(get: Int)
  implicit def arbSmallNonnegative = Arbitrary(Gen.choose(0,20).map(SmallNonnegative(_)))

  object PureStream {
    def singleChunk[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map(as => PureStream("single chunk", Stream.emits(as)))
    }
    def unchunked[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map(as => PureStream("unchunked", Stream.emits(as).unchunk))
    }
    def leftAssociated[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map { as =>
        val s = as.foldLeft(Stream.empty[Pure,A])((acc,a) => acc ++ Stream.emit(a))
        PureStream("left-associated", s)
      }
    }
    def rightAssociated[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map { as =>
        val s = Chunk.seq(as).foldRight(Stream.empty[Pure,A])((a,acc) => Stream.emit(a) ++ acc)
        PureStream("right-associated", s)
      }
    }
    def randomlyChunked[A:Arbitrary]: Gen[PureStream[A]] = Gen.sized { size =>
      nestedVectorGen[A](0, size, true).map { chunks =>
        PureStream("randomly-chunked", Stream.emits(chunks).flatMap(Stream.emits))
      }
    }
    def uniformlyChunked[A:Arbitrary]: Gen[PureStream[A]] = Gen.sized { size =>
      for {
        n <- Gen.choose(0, size)
        chunkSize <- Gen.choose(0, 10)
        chunks <- Gen.listOfN(n, Gen.listOfN(chunkSize, Arbitrary.arbitrary[A]))
      } yield PureStream(s"uniformly-chunked ($n) ($chunkSize)",
                         Stream.emits(chunks).flatMap(Stream.emits))
    }

    def gen[A:Arbitrary]: Gen[PureStream[A]] =
      Gen.oneOf(
        rightAssociated[A], leftAssociated[A], singleChunk[A],
        unchunked[A], randomlyChunked[A], uniformlyChunked[A])
  }

  case object Err extends RuntimeException("oh noes!!")

  /** Newtype for generating various failing streams. */
  case class Failure(tag: String, get: Stream[Task,Int])

  implicit def failingStreamArb: Arbitrary[Failure] = Arbitrary(
    Gen.oneOf[Failure](
      Failure("pure-failure", Stream.fail(Err)),
      Failure("failure-inside-effect", Stream.eval(Task.delay(throw Err))),
      Failure("failure-mid-effect", Stream.eval(Task.now(()).flatMap(_ => throw Err))),
      Failure("failure-in-pure-code", Stream.emit(42).map(_ => throw Err)),
      Failure("failure-in-pure-code(2)", Stream.emit(42).flatMap(_ => throw Err)),
      Failure("failure-in-pure-pull", Stream.emit[Task,Int](42).pull(h => throw Err)),
      Failure("failure-in-async-code",
        Stream.eval[Task,Int](Task.delay(throw Err)).pull { h =>
          h.invAwaitAsync.flatMap(_.force).flatMap(identity) })
    )
  )

  def nestedVectorGen[A:Arbitrary](minSize: Int, maxSize: Int, emptyChunks: Boolean = false)
    : Gen[Vector[Vector[A]]]
    = for {
      sizeOuter <- Gen.choose(minSize, maxSize)
      inner = Gen.choose(if (emptyChunks) 0 else 1, 10) flatMap { sz =>
        Gen.listOfN(sz, Arbitrary.arbitrary[A]).map(_.toVector)
      }
      outer <- Gen.listOfN(sizeOuter, inner).map(_.toVector)
    } yield outer

  val nonEmptyNestedVectorGen: Gen[Vector[Vector[Int]]] = nestedVectorGen[Int](1,10)
}

object TestUtil extends TestUtil
