package fs2
import org.scalacheck.Gen

class RepeatSpec extends Fs2Spec {

  // avoid running on huge streams
  val smallPositiveInt: Gen[Int] = Gen.chooseNum(1, 200)
  val nonEmptyIntList: Gen[List[Int]] = Gen.nonEmptyListOf(smallPositiveInt)

  "Stream" - {
    "repeat" in {
      forAll(smallPositiveInt, nonEmptyIntList) { (length: Int, testValues: List[Int]) =>
        val stream = Stream.emits(testValues)
        stream.repeat.take(length).compile.toList shouldBe List.fill(length / testValues.size + 1)(testValues).flatten.take(length)
      }
    }

    "repeatN" in {
      forAll(smallPositiveInt, nonEmptyIntList) { (n: Int, testValues: List[Int]) =>
        val stream = Stream.emits(testValues)
        stream.repeatN(n).compile.toList shouldBe List.fill(n)(testValues).flatten
      }
    }
  }
}
