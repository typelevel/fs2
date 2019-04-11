package fs2

import scala.concurrent.duration._
import cats.effect._
import cats.data.NonEmptyList

import cats.laws.discipline.arbitrary._
import TestUtil._

class GroupWithinSpec extends Fs2Spec {
  "groupWithin should never lose any elements" in forAll {
    (s: PureStream[VeryShortFiniteDuration], d: ShortFiniteDuration, maxGroupSize: SmallPositive) =>
      val result = s.get.toVector
      val action =
        s.get
          .covary[IO]
          .evalTap(shortDuration => IO.sleep(shortDuration.get))
          .groupWithin(maxGroupSize.get, d.get)
          .flatMap(s => Stream.emits(s.toList))

      runLog(action) shouldBe (result)
  }

  "groupWithin should never emit empty groups" in forAll {
    (s: PureStream[VeryShortFiniteDuration], d: ShortFiniteDuration, maxGroupSize: SmallPositive) =>
      whenever(s.get.toVector.nonEmpty) {
        val action =
          s.get
            .covary[IO]
            .evalTap(shortDuration => IO.sleep(shortDuration.get))
            .groupWithin(maxGroupSize.get, d.get)
            .map(_.toList)

        runLog(action).foreach(group => group should not be empty)
      }
  }

  "groupWithin should never have more elements than in its specified limit" in forAll {
    (s: PureStream[VeryShortFiniteDuration], d: ShortFiniteDuration, maxGroupSize: SmallPositive) =>
      val maxGroupSizeAsInt = maxGroupSize.get
      val action =
        s.get
          .covary[IO]
          .evalTap(shortDuration => IO.sleep(shortDuration.get))
          .groupWithin(maxGroupSizeAsInt, d.get)
          .map(_.toList.size)

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
        fullResult.head.toList shouldBe streamAsList.toList
      }
  }

}
