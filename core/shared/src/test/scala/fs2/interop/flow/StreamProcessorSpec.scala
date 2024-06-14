package fs2
package interop
package flow

import cats.effect.{IO, Resource}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.effect.PropF.forAllF
import java.util.concurrent.Flow.Publisher

final class StreamProcessorSpec extends Fs2Suite {
  test("should process upstream input and propagate results to downstream") {
    forAllF(Arbitrary.arbitrary[Seq[Int]], Gen.posNum[Int]) { (ints, bufferSize) =>
      val processor = pipeToProcessor[IO, Int, Int](
        pipe = stream => stream.map(_ * 1),
        chunkSize = bufferSize
      )

      val publisher = toPublisher(
        Stream.emits(ints).covary[IO]
      )

      def subscriber(publisher: Publisher[Int]): IO[Vector[Int]] =
        Stream
          .fromPublisher[IO](
            publisher,
            chunkSize = bufferSize
          )
          .compile
          .toVector

      val program = Resource.both(processor, publisher).use { case (pr, p) =>
        IO(p.subscribe(pr)) >> subscriber(pr)
      }

      program.assertEquals(ints.toVector)
    }
  }
}
