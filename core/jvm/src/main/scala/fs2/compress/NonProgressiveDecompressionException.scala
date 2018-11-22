package fs2.compress

final case class NonProgressiveDecompressionException(bufferSize: Int)
    extends RuntimeException(s"buffer size $bufferSize is too small; gunzip cannot make progress")
