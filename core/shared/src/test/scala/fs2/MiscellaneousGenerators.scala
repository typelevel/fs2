package fs2

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary

trait MiscellaneousGenerators {
  val throwableGenerator: Gen[Throwable] =
    Gen.const(new Err)
  implicit val throwableArbitrary: Arbitrary[Throwable] =
    Arbitrary(throwableGenerator)

  def arrayGenerator[A: reflect.ClassTag](genA: Gen[A]): Gen[Array[A]] =
    Gen.chooseNum(0, 100).flatMap(n => Gen.listOfN(n, genA)).map(_.toArray)
  implicit def arrayArbitrary[A: Arbitrary: reflect.ClassTag]: Arbitrary[Array[A]] =
    Arbitrary(arrayGenerator(arbitrary[A]))

  def smallLists[A](genA: Gen[A]): Gen[List[A]] =
    Gen.posNum[Int].flatMap(n0 => Gen.listOfN(n0 % 20, genA))
}
