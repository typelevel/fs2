package scalaz.stream

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import scalaz.Equal
import scalaz.std.anyVal._
import scalaz.syntax.equal._
import scalaz.concurrent.Task
import scodec.bits.ByteVector

import Process._
import ReceiveY._
import process1._

object TestInstances {
  implicit val arbitraryByteVector: Arbitrary[ByteVector] =
    Arbitrary(arbitrary[String].map(s => ByteVector.view(s.getBytes)))

  implicit def arbitraryIndexedSeq[A: Arbitrary]: Arbitrary[IndexedSeq[A]] =
    Arbitrary(arbitrary[List[A]].map(_.toIndexedSeq))

  implicit def arbitraryProcess0[A: Arbitrary]: Arbitrary[Process0[A]] =
    Arbitrary(arbitrary[List[A]].map(list => emitAll(list)))

  implicit def arbitraryLabeledProcess1[A]: Arbitrary[(Process1[A,A], String)] = {
    val ps0Gen: Gen[(Process1[A,A], String)] =
      Gen.oneOf(Seq(
        (await1, "await1"),
        (bufferAll, "bufferAll"),
        (dropLast, "dropLast"),
        (halt, "halt"),
        (id, "id"),
        (last, "last"),
        (skip, "skip")))

    val ps1Int: Seq[Int => (Process1[A,A], String)] =
      Seq(
        i => (buffer(i), s"buffer($i)"),
        i => (drop(i), s"drop($i)"),
        i => (take(i), s"take($i)"))

    val ps1IntGen: Gen[(Process1[A,A], String)] =
      Gen.oneOf(ps1Int).flatMap(p => Gen.posNum[Int].map(i => p(i)))

    Arbitrary(Gen.oneOf(ps0Gen, ps1IntGen))
  }

  implicit def arbitraryProcess1[A]: Arbitrary[Process1[A, A]] =
    Arbitrary(arbitraryLabeledProcess1.arbitrary.map(
      (x: (Process1[A, A], String)) => x._1))

  implicit val arbitraryLabeledProcess1IntInt: Arbitrary[(Process1[Int,Int], String)] = {
    val ps0Gen: Gen[(Process1[Int,Int], String)] =
      Gen.oneOf(Seq(
        (prefixSums[Int], "prefixSums"),
        (sum[Int], "sum")))

    val ps1: Seq[Int => (Process1[Int,Int], String)] =
      Seq(
        i => (intersperse(i), s"intersperse($i)"),
        i => (lastOr(i), s"lastOr($i)"),
        i => (lift((_: Int) * i), s"lift(_ * $i)"),
        i => (shiftRight(i), s"shiftRight($i)"))

    val ps1Gen: Gen[(Process1[Int,Int], String)] =
      Gen.oneOf(ps1).flatMap(p => arbitrary[Int].map(i => p(i)))

    Arbitrary(Gen.oneOf(ps0Gen, ps1Gen, arbitraryLabeledProcess1[Int].arbitrary))
  }

  implicit val arbitraryProcess1IntInt: Arbitrary[Process1[Int,Int]] =
    Arbitrary(arbitraryLabeledProcess1IntInt.arbitrary.map(
      (x: (Process1[Int,Int], String)) => x._1))

  implicit def arbitraryReceiveY[A: Arbitrary,B: Arbitrary]: Arbitrary[ReceiveY[A,B]] =
    Arbitrary(Gen.oneOf(
      arbitrary[A].map(ReceiveL(_)),
      arbitrary[B].map(ReceiveR(_))
    ))

  implicit def equalProcess0[A: Equal]: Equal[Process0[A]] =
    Equal.equal(_.toList == _.toList)

  implicit val equalProcess1IntInt: Equal[Process1[Int,Int]] =
    Equal.equal { (a, b) =>
      val p = range(-10, 10) ++
        Process(Int.MaxValue - 1, Int.MaxValue) ++
        Process(Int.MinValue + 1, Int.MinValue) ++
        Process(Int.MinValue >> 1, Int.MaxValue >> 1)

      p.pipe(a) === p.pipe(b)
    }

  implicit def equalProcessTask[A:Equal]: Equal[Process[Task,A]] =
    Equal.equal(_.runLog.attemptRun == _.runLog.attemptRun)
}
