package fs2

import scala.concurrent.duration._
import cats.effect._
import cats.data.NonEmptyList

import cats.laws.discipline.arbitrary._
import TestUtil._

class GroupWithinSpec extends Fs2Spec {
  "groupWithin should never lose any elements" in forAll {
    (s: PureStream[VeryShortFiniteDuration],
     d: VeryShortFiniteDuration,
     maxGroupSize: SmallPositive) =>
      val result = s.get.toVector
      val action =
        s.get
          .covary[IO]
          .observe1(shortDuration => IO.sleep(shortDuration.get))
          .groupWithin(maxGroupSize.get, d.get)
          .flatMap(s => Stream.emits(s.force.toList))

      runLog(action) shouldBe (result)
  }

  "groupWithin should never have more elements than in its specified limit" in forAll {
    (s: PureStream[VeryShortFiniteDuration], d: ShortFiniteDuration, maxGroupSize: SmallPositive) =>
      val maxGroupSizeAsInt = maxGroupSize.get
      val action =
        s.get
          .covary[IO]
          .observe1(shortDuration => IO.sleep(shortDuration.get))
          .groupWithin(maxGroupSizeAsInt, d.get)
          .map(_.force.toList.size)

      runLog(action).foreach(size => size shouldBe <=(maxGroupSizeAsInt))
  }

  "groupWithin should return a finite stream back in a single chunk given a group size equal to the stream size and an absurdly high duration" in forAll {
    (streamAsList: NonEmptyList[Int]) =>
      val action =
        Stream
          .emits(streamAsList.toList)
          .covary[IO]
          .groupWithin(streamAsList.size, (Long.MaxValue - 1L).nanoseconds)

      val fullResult = runLog(action)
      withClue(s"Our full result looked like: $fullResult") {
        fullResult.head.force.toList shouldBe streamAsList.toList
      }
  }

  "groupWithin should timeout with an empty chunk if the upstream stream never returns" in forAll {
    (n: SmallPositive, d: ShortFiniteDuration) =>
      val grouped = Stream.eval(IO.never).groupWithin(n.get, d.get).head
      runLog(grouped).head.force.toList shouldBe List.empty
  }

}
