package fs2

import scala.collection.immutable.ArraySeq

class ArraySeqCollectorSuite extends Fs2Suite {

  test("work for a tagged ArraySeq") {
    assertEquals(Stream.range(1, 5).compile.to(ArraySeq), ArraySeq.range(1, 5))
  }

  test("work for an untagged ArraySeq") {
    assertEquals(Stream.range(1, 5).compile.to(ArraySeq.untagged), ArraySeq.range(1, 5))
  }
}
