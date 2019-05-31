package fs2

import org.scalatest.prop._
import CommonGenerators._

trait StreamGenerators {

  private def smallLists[A: Generator]: Generator[List[A]] =
    lists[A].havingSizesBetween(0, 20)

  implicit def pureStreamGenerator[A: Generator]: Generator[Stream[Pure, A]] =
    frequency(
      1 -> specificValue(Stream.empty),
      5 -> smallLists[A].map(as => Stream.emits(as)),
      5 -> smallLists[A].map(as => Stream.emits(as).unchunk),
      5 -> smallLists(smallLists[A]).map(_.foldLeft(Stream.empty.covaryOutput[A])((acc, as) =>
        acc ++ Stream.emits(as))),
      5 -> smallLists(smallLists[A]).map(_.foldRight(Stream.empty.covaryOutput[A])((as, acc) =>
        Stream.emits(as) ++ acc))
    )
}
