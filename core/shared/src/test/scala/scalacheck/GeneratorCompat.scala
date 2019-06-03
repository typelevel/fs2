package org.scalacheck

import org.scalatest.prop.{Generator, Randomizer, SizeParam}
import org.scalacheck.rng.Seed

object GeneratorCompat {
  def genFromGenerator[A](g: Generator[A]): Gen[A] =
    Gen.gen[A] { (p, seed) =>
      val (n, _, rndNext) = g.next(SizeParam(0, 10, 10), Nil, Randomizer(seed.long._1))
      Gen.r[A](Some(n), Seed(rndNext.nextLong._1))
    }

  implicit def arbitraryFromGenerator[A](implicit g: Generator[A]): Arbitrary[A] =
    Arbitrary(genFromGenerator(g))
}