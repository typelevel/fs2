package fs2

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
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
      println(s"Our test input: $streamAsList")
      val action =
        Stream
          .emits(streamAsList.toList)
          .covary[IO]
          .groupWithin(streamAsList.size, FiniteDuration(Long.MaxValue - 1L, TimeUnit.NANOSECONDS))

      val fullResult = runLog(action)
      withClue(s"Our full result looked like: $fullResult") {
        fullResult.head.force.toList shouldBe streamAsList.toList
      }
  }

}
// "A GroupedWithin" must {

//   "group elements within the duration" taggedAs TimingTest in assertAllStagesStopped {
//     val input = Iterator.from(1)
//     val p = TestPublisher.manualProbe[Int]()
//     val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
//     Source.fromPublisher(p).groupedWithin(1000, 1.second).to(Sink.fromSubscriber(c)).run()
//     val pSub = p.expectSubscription
//     val cSub = c.expectSubscription
//     cSub.request(100)
//     val demand1 = pSub.expectRequest.toInt
//     (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
//     val demand2 = pSub.expectRequest.toInt
//     (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
//     val demand3 = pSub.expectRequest.toInt
//     c.expectNext((1 to (demand1 + demand2).toInt).toVector)
//     (1 to demand3) foreach { _ ⇒ pSub.sendNext(input.next()) }
//     c.expectNoMsg(300.millis)
//     c.expectNext(((demand1 + demand2 + 1).toInt to (demand1 + demand2 + demand3).toInt).toVector)
//     c.expectNoMsg(300.millis)
//     pSub.expectRequest
//     val last = input.next()
//     pSub.sendNext(last)
//     pSub.sendComplete()
//     c.expectNext(List(last))
//     c.expectComplete
//     c.expectNoMsg(200.millis)
//   }

//   "deliver bufferd elements onComplete before the timeout" taggedAs TimingTest in {
//     val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
//     Source(1 to 3).groupedWithin(1000, 10.second).to(Sink.fromSubscriber(c)).run()
//     val cSub = c.expectSubscription
//     cSub.request(100)
//     c.expectNext((1 to 3).toList)
//     c.expectComplete
//     c.expectNoMsg(200.millis)
//   }

//   "buffer groups until requested from downstream" taggedAs TimingTest in {
//     val input = Iterator.from(1)
//     val p = TestPublisher.manualProbe[Int]()
//     val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
//     Source.fromPublisher(p).groupedWithin(1000, 1.second).to(Sink.fromSubscriber(c)).run()
//     val pSub = p.expectSubscription
//     val cSub = c.expectSubscription
//     cSub.request(1)
//     val demand1 = pSub.expectRequest.toInt
//     (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
//     c.expectNext((1 to demand1).toVector)
//     val demand2 = pSub.expectRequest.toInt
//     (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
//     c.expectNoMsg(300.millis)
//     cSub.request(1)
//     c.expectNext(((demand1 + 1) to (demand1 + demand2)).toVector)
//     pSub.sendComplete()
//     c.expectComplete
//     c.expectNoMsg(100.millis)
//   }

//   "drop empty groups" taggedAs TimingTest in {
//     val p = TestPublisher.manualProbe[Int]()
//     val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
//     Source.fromPublisher(p).groupedWithin(1000, 500.millis).to(Sink.fromSubscriber(c)).run()
//     val pSub = p.expectSubscription
//     val cSub = c.expectSubscription
//     cSub.request(2)
//     pSub.expectRequest
//     c.expectNoMsg(600.millis)
//     pSub.sendNext(1)
//     pSub.sendNext(2)
//     c.expectNext(List(1, 2))
//     // nothing more requested
//     c.expectNoMsg(1100.millis)
//     cSub.request(3)
//     c.expectNoMsg(600.millis)
//     pSub.sendComplete()
//     c.expectComplete
//   }

//   "not emit empty group when finished while not being pushed" taggedAs TimingTest in {
//     val p = TestPublisher.manualProbe[Int]()
//     val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
//     Source.fromPublisher(p).groupedWithin(1000, 50.millis).to(Sink.fromSubscriber(c)).run()
//     val pSub = p.expectSubscription
//     val cSub = c.expectSubscription
//     cSub.request(1)
//     pSub.expectRequest
//     pSub.sendComplete
//     c.expectComplete
//   }

//   "reset time window when max elements reached" taggedAs TimingTest in {
//     val upstream = TestPublisher.probe[Int]()
//     val downstream = TestSubscriber.probe[immutable.Seq[Int]]()
//     Source.fromPublisher(upstream).groupedWithin(3, 2.second).to(Sink.fromSubscriber(downstream)).run()

//     downstream.request(2)
//     downstream.expectNoMsg(1000.millis)

//     (1 to 4).foreach(upstream.sendNext)
//     downstream.within(1000.millis) {
//       downstream.expectNext((1 to 3).toVector)
//     }

//     downstream.expectNoMsg(1500.millis)

//     downstream.within(1000.millis) {
//       downstream.expectNext(List(4))
//     }

//     upstream.sendComplete()
//     downstream.expectComplete()
//     downstream.expectNoMsg(100.millis)
//   }

//   "reset time window when exact max elements reached" taggedAs TimingTest in {
//     val upstream = TestPublisher.probe[Int]()
//     val downstream = TestSubscriber.probe[immutable.Seq[Int]]()
//     Source.fromPublisher(upstream).groupedWithin(3, 1.second).to(Sink.fromSubscriber(downstream)).run()

//     downstream.request(2)

//     (1 to 3).foreach(upstream.sendNext)
//     downstream.within(1000.millis) {
//       downstream.expectNext((1 to 3).toVector)
//     }

//     upstream.sendComplete()
//     downstream.expectComplete()
//   }

//   "group evenly" taggedAs TimingTest in {
//     def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) → Seq(immutable.Seq(x, y, z)) }: _*)
//     TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.groupedWithin(3, 10.minutes)))
//   }

//   "group with rest" taggedAs TimingTest in {
//     def script = Script((TestConfig.RandomTestRange.map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) → Seq(immutable.Seq(x, y, z)) }
//       :+ { val x = random.nextInt(); Seq(x) → Seq(immutable.Seq(x)) }): _*)
//     TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.groupedWithin(3, 10.minutes)))
//   }

//   "group with small groups with backpressure" taggedAs TimingTest in {
//     Source(1 to 10)
//       .groupedWithin(1, 1.day)
//       .throttle(1, 110.millis, 0, ThrottleMode.Shaping)
//       .runWith(Sink.seq).futureValue should ===((1 to 10).map(List(_)))
//   }

// }
