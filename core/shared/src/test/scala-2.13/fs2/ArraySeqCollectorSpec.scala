package fs2

import scala.collection.immutable.ArraySeq

class ArraySeqCollectorSpec extends Fs2Spec {

  "the ArraySeq collector" - {
    "work for a tagged ArraySeq" in {
      assert(Stream.range(1, 5).compile.to(ArraySeq) == ArraySeq.range(1, 5))
    }

    "work for an untagged ArraySeq" in {
      assert(Stream.range(1, 5).compile.to(ArraySeq.untagged) === ArraySeq.range(1, 5))
    }
  }

}
