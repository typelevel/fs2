package fs2

import java.util.concurrent.TimeoutException
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.duration._

import fs2.util.NonFatal

object TestStrategy {
  implicit val S = Strategy.fromFixedDaemonPool(8)
  implicit lazy val scheduler = Scheduler.fromFixedDaemonPool(2)
}

trait TestUtil {

 implicit val S = TestStrategy.S
 implicit val scheduler = TestStrategy.scheduler

  def runLog[A](s: Stream[Task,A], timeout: FiniteDuration = 1.minute): Vector[A] = s.runLog.unsafeRunFor(timeout)

  def throws[A](err: Throwable)(s: Stream[Task,A]): Boolean =
    s.runLog.unsafeAttemptRun() match {
      case Left(e) if e == err => true
      case _ => false
    }

  def spuriousFail(s: Stream[Task,Int], f: Failure): Stream[Task,Int] =
    s.flatMap { i => if (i % (math.random * 10 + 1).toInt == 0) f.get
                     else Stream.emit(i) }

  def swallow(a: => Any): Unit =
    try { a; () }
    catch {
      case e: InterruptedException => throw e
      case e: TimeoutException => throw e
      case NonFatal(e) => ()
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
          h.invAwaitAsync.flatMap(_.pull).flatMap(identity) })
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
