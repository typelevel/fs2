package fs2

import org.scalacheck.{Arbitrary, Gen}

trait Generators {

  private def smallLists[A](genA: Gen[A]): Gen[List[A]] =
    Gen.posNum[Int].flatMap(n0 => Gen.listOfN(n0 % 20, genA))

  implicit def pureStreamGenerator[A: Arbitrary]: Arbitrary[Stream[Pure, A]] = Arbitrary {
    val genA = Arbitrary.arbitrary[A]
    Gen.frequency(
      1 -> Gen.const(Stream.empty),
      5 -> smallLists(genA).map(as => Stream.emits(as)),
      5 -> smallLists(genA).map(as => Stream.emits(as).unchunk),
      5 -> smallLists(smallLists(genA))
        .map(_.foldLeft(Stream.empty.covaryOutput[A])((acc, as) => acc ++ Stream.emits(as))),
      5 -> smallLists(smallLists(genA))
        .map(_.foldRight(Stream.empty.covaryOutput[A])((as, acc) => Stream.emits(as) ++ acc))
    )
  }
}