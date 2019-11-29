package spinoco.fs2.crypto.internal

import cats.data.Chain
import fs2._
import scodec.bits.ByteVector


object util {

  def concatBytes(a: Chunk[Byte], b: Chunk[Byte]): Chunk[Byte] =
    concatBytes(Chain(a, b))


  def concatBytes(l: Chain[Chunk[Byte]]): Chunk[Byte] = {
    Chunk.ByteVectorChunk(l.foldLeft(ByteVector.empty) { 
      case (bv, Chunk.ByteVectorChunk(bv1)) => 
        bv ++ bv1
      case (bv, ch) =>
        val bs = ch.toBytes 
        bv ++ ByteVector.view(bs.values, bs.offset, bs.size) 
    }) 
  }

}
