package fs2

import java.util.concurrent.TimeoutException
import org.scalacheck.{Arbitrary, Cogen, Gen, Shrink}

import scala.concurrent.Future
import scala.concurrent.duration._

import cats.effect.IO
import cats.implicits._
import fs2.internal.NonFatal

trait TestUtil extends TestUtilPlatform {

  val timeout: FiniteDuration = 60.seconds

  def runLogF[A](s: Stream[IO,A]): Future[Vector[A]] = (IO.shift *> s.runLog).unsafeToFuture

  def spuriousFail(s: Stream[IO,Int], f: Failure): Stream[IO,Int] =
    s.flatMap { i => if (i % (math.random * 10 + 1).toInt == 0) f.get
                     else Stream.emit(i) }

  def swallow(a: => Any): Unit =
    try { a; () }
    catch {
      case e: InterruptedException => throw e
      case e: TimeoutException => throw e
      case Err => ()
      case NonFatal(e) => e.printStackTrace; ()
    }

  implicit def arbChunk[A](implicit A: Arbitrary[A]): Arbitrary[Chunk[A]] = Arbitrary(
    Gen.frequency(
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.indexedSeq(as.toVector)),
      10 -> Gen.listOf(A.arbitrary).map(Chunk.seq),
      5 -> A.arbitrary.map(a => Chunk.singleton(a)),
      1 -> Chunk.empty[A]
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
        val s = as.foldLeft(Stream.empty.covaryOutput[A])((acc,a) => acc ++ Stream.emit(a))
        PureStream("left-associated", s)
      }
    }
    def rightAssociated[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map { as =>
        val s = as.foldRight(Stream.empty.covaryOutput[A])((a,acc) => Stream.emit(a) ++ acc)
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

    implicit def pureStreamCoGen[A: Cogen]: Cogen[PureStream[A]] = Cogen[List[A]].contramap[PureStream[A]](_.get.toList)

    implicit def pureStreamShrink[A]: Shrink[PureStream[A]] = Shrink { s => Shrink.shrink(s.get.toList).map(as => PureStream("shrunk", Stream.chunk(Chunk.seq(as)))) }
  }

  case object Err extends RuntimeException("oh noes!!")

  /** Newtype for generating various failing streams. */
  case class Failure(tag: String, get: Stream[IO,Int])

  implicit def failingStreamArb: Arbitrary[Failure] = Arbitrary(
    Gen.oneOf[Failure](
      Failure("pure-failure", Stream.raiseError(Err)),
      Failure("failure-inside-effect", Stream.eval(IO(throw Err))),
      Failure("failure-mid-effect", Stream.eval(IO.pure(()).flatMap(_ => throw Err))),
      Failure("failure-in-pure-code", Stream.emit(42).map(_ => throw Err)),
      Failure("failure-in-pure-code(2)", Stream.emit(42).flatMap(_ => throw Err)),
      Failure("failure-in-pure-pull", Stream.emit(42).pull.uncons.map(_ => throw Err).stream),
      Failure("failure-in-async-code",
        Stream.eval[IO,Int](IO(throw Err)).pull.unconsAsync.flatMap { _.pull.flatMap {
          case None => Pull.pure(())
          case Some((hd,tl)) => Pull.output(hd) *> Pull.pure(())
        }}.stream)
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
