package fs2.protocols.pcapng

import scodec.Codec
import scodec.bits._
import scodec.codecs._

case class UnrecognizedBlock(blockType: ByteVector, length: Length, bytes: ByteVector) extends BodyBlock

object UnrecognizedBlock {

  def codec(implicit ord: ByteOrdering): Codec[UnrecognizedBlock] =
    "UB" | Block.codecUnrecognized.as[UnrecognizedBlock]
}
