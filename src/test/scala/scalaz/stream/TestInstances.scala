package scalaz.stream

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import scalaz.Equal
import scodec.bits.ByteVector

import Process._
import ReceiveY._

object TestInstances {
  implicit val arbitraryByteVector: Arbitrary[ByteVector] =
    Arbitrary(arbitrary[String].map(s => ByteVector.view(s.getBytes)))

  implicit def arbitraryIndexedSeq[A: Arbitrary]: Arbitrary[IndexedSeq[A]] =
    Arbitrary(arbitrary[List[A]].map(_.toIndexedSeq))

  implicit def arbitraryProcess0[A: Arbitrary]: Arbitrary[Process0[A]] =
    Arbitrary(arbitrary[List[A]].map(list => emitSeq(list)))

  implicit def arbitraryReceiveY[A: Arbitrary,B: Arbitrary]: Arbitrary[ReceiveY[A,B]] =
    Arbitrary(Gen.oneOf(
      arbitrary[A].map(ReceiveL(_)),
      arbitrary[B].map(ReceiveR(_))
    ))

  implicit def equalProcess0[A: Equal]: Equal[Process0[A]] =
    Equal.equal(_.toList == _.toList)
}
