package fs2

import java.util.concurrent.TimeoutException
import org.scalacheck.{Arbitrary, Cogen, Gen, Shrink}

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.concurrent.duration._

import cats.effect.IO
import cats.implicits._

object TestUtil extends TestUtilPlatform with ChunkGen {

  def runLogF[A](s: Stream[IO,A]): Future[Vector[A]] = (IO.shift(executionContext) >> s.compile.toVector).unsafeToFuture

  def spuriousFail(s: Stream[IO,Int], f: Failure): Stream[IO,Int] =
    s.flatMap { i => if (i % (math.random * 10 + 1).toInt == 0) f.get
                     else Stream.emit(i) }

  def swallow(a: => Any): Unit =
    try { a; () }
    catch {
      case e: InterruptedException => throw e
      case e: TimeoutException => throw e
      case e: Err => ()
      case NonFatal(e) => ()
    }

  /** Newtype for generating test cases. Use the `tag` for labeling properties. */
  case class PureStream[+A](tag: String, get: Stream[Pure,A])
  implicit def arbPureStream[A:Arbitrary] = Arbitrary(PureStream.gen[A])

  case class SmallPositive(get: Int)
  implicit def arbSmallPositive = Arbitrary(Gen.choose(1,20).map(SmallPositive(_)))

  case class SmallNonnegative(get: Int)
  implicit def arbSmallNonnegative = Arbitrary(Gen.choose(0,20).map(SmallNonnegative(_)))

    case class ShortFiniteDuration(get: FiniteDuration)
   implicit def arbShortFiniteDuration = Arbitrary{
     Gen.choose(1L, 50000L)
       .map(_.microseconds)
       .map(ShortFiniteDuration(_))
   }

   case class VeryShortFiniteDuration(get: FiniteDuration)
   implicit def arbVeryShortFiniteDuration = Arbitrary{
     Gen.choose(1L, 500L)
       .map(_.microseconds)
       .map(VeryShortFiniteDuration(_))
   }

  object PureStream {
    def singleChunk[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map(as => PureStream(s"single chunk: $size $as", Stream.emits(as)))
    }
    def unchunked[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map(as => PureStream(s"unchunked: $size $as", Stream.emits(as).unchunk))
    }
    def leftAssociated[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map { as =>
        val s = as.foldLeft(Stream.empty.covaryOutput[A])((acc,a) => acc ++ Stream.emit(a))
        PureStream(s"left-associated : $size $as", s)
      }
    }
    def rightAssociated[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map { as =>
        val s = as.foldRight(Stream.empty.covaryOutput[A])((a,acc) => Stream.emit(a) ++ acc)
        PureStream(s"right-associated : $size $as", s)
      }
    }
    def randomlyChunked[A:Arbitrary]: Gen[PureStream[A]] = Gen.sized { size =>
      nestedVectorGen[A](0, size, true).map { chunks =>
        PureStream(s"randomly-chunked: $size ${chunks.map(_.size)}", Stream.emits(chunks).flatMap(Stream.emits))
      }
    }
    def filtered[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] =
      Gen.sized { size =>
        A.arbitrary.flatMap { a =>
          Gen.listOfN(size, A.arbitrary).map(as =>
            PureStream(s"filtered: $a $size $as", Stream.emits(as).unchunk.filter(_ == a)))
        }
      }
    def uniformlyChunked[A:Arbitrary]: Gen[PureStream[A]] = Gen.sized { size =>
      for {
        n <- Gen.choose(0, size)
        chunkSize <- Gen.choose(0, 10)
        chunks <- Gen.listOfN(n, Gen.listOfN(chunkSize, Arbitrary.arbitrary[A]))
      } yield PureStream(s"uniformly-chunked $size $n ($chunkSize)",
                         Stream.emits(chunks).flatMap(Stream.emits))
    }

    def gen[A:Arbitrary]: Gen[PureStream[A]] =
      Gen.oneOf(
        rightAssociated[A], leftAssociated[A], singleChunk[A],
        unchunked[A], randomlyChunked[A], uniformlyChunked[A],
        filtered[A])

    implicit def pureStreamCoGen[A: Cogen]: Cogen[PureStream[A]] = implicitly[Cogen[List[A]]].contramap[PureStream[A]](_.get.toList)

    implicit def pureStreamShrink[A]: Shrink[PureStream[A]] = Shrink { s => Shrink.shrink(s.get.toList).map(as => PureStream(s"shrunk: ${as.size} from ${s.tag}", Stream.chunk(Chunk.seq(as)))) }
  }

  class Err extends RuntimeException("oh noes!!")

  /** Newtype for generating various failing streams. */
  case class Failure(tag: String, get: Stream[IO,Int])

  implicit def failingStreamArb: Arbitrary[Failure] = Arbitrary(
    Gen.oneOf[Failure](
      Failure("pure-failure", Stream.raiseError[IO](new Err)),
      Failure("failure-inside-effect", Stream.eval(IO(throw new Err))),
      Failure("failure-mid-effect", Stream.eval(IO.pure(()).flatMap(_ => throw new Err))),
      Failure("failure-in-pure-code", Stream.emit(42).map(_ => throw new Err)),
      Failure("failure-in-pure-code(2)", Stream.emit(42).flatMap(_ => throw new Err)),
      Failure("failure-in-pure-pull", Stream.emit(42).pull.uncons.map(_ => throw new Err).stream)
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
