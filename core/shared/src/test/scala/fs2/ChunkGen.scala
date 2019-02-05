package fs2

import cats.data.Chain
import cats.laws.discipline.arbitrary._
import org.scalacheck._
import Arbitrary.arbitrary
import scala.reflect.ClassTag

trait ChunkGen {

  implicit def arbChunk[A: ClassTag](implicit A: Arbitrary[A]): Arbitrary[Chunk[A]] = Arbitrary(
    Gen.frequency(
      1 -> Chunk.empty[A],
      5 -> A.arbitrary.map(a => Chunk.singleton(a)),
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.vector(as.toVector)),
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.indexedSeq(as.toIndexedSeq)),
      10 -> Gen.listOf(A.arbitrary).map(Chunk.seq),
      10 -> arbitrary[Chain[A]].map(Chunk.chain),
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.buffer(collection.mutable.Buffer.empty[A] ++ as)),
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.array(as.toArray)),
      10 -> (for {
        as <- Gen.listOf(A.arbitrary)
        offset <- Gen.chooseNum(0, as.size / 2)
        len <- Gen.chooseNum(0, as.size - offset)
      } yield Chunk.boxed(as.toArray, offset, len))
    )
  )

  implicit def cogenChunk[A: Cogen]: Cogen[Chunk[A]] =
    Cogen[List[A]].contramap(_.toList)
}

object ChunkGen extends ChunkGen