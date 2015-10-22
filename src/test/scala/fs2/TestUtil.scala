package fs2

import fs2.util.{Sub1,Task}
import org.scalacheck.{Arbitrary, Gen}

object TestUtil {

  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  implicit class EqualsOp[F[_],A](s: Stream[F,A])(implicit S: Sub1[F,Task]) {
    def ===(v: Vector[A]) = run(s.covary) == v
    def ==?(v: Vector[A]) = {
      val l = run(s.covary)
      val r = v
      l == r || { println("left: " + l); println("right: " + r); false }
    }
  }

  /** Newtype for generating test cases. */
  case class PureStream[+A](tag: String, stream: Stream[Pure,A])

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
      nestedVectorGen(0, size, true).map { chunks =>
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
      Gen.oneOf(rightAssociated, leftAssociated, singleChunk, unchunked, randomlyChunked, uniformlyChunked)
  }

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
