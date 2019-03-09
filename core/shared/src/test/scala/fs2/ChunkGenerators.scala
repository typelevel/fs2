package fs2

import cats.data.Chain
import scala.reflect.ClassTag
import scodec.bits.ByteVector
import java.nio.{
  Buffer => JBuffer,
  CharBuffer => JCharBuffer,
  ByteBuffer => JByteBuffer,
  ShortBuffer => JShortBuffer,
  IntBuffer => JIntBuffer,
  DoubleBuffer => JDoubleBuffer,
  LongBuffer => JLongBuffer,
  FloatBuffer => JFloatBuffer
}

import org.scalactic.anyvals.PosZInt
import org.scalatest.prop.{CommonGenerators, Generator, Randomizer, SizeParam}
import CommonGenerators._

trait ChunkGeneratorsLowPriority1 {

  protected def withShrinker[A](g: Generator[A])(
      shrinker: (A, Randomizer) => (Iterator[A], Randomizer)): Generator[A] = new Generator[A] {
    override def initEdges(maxLength: PosZInt, rnd: Randomizer): (List[A], Randomizer) =
      g.initEdges(maxLength, rnd)
    override def next(szp: SizeParam, edges: List[A], rnd: Randomizer): (A, List[A], Randomizer) =
      g.next(szp, edges, rnd)
    override def shrink(value: A, rnd: Randomizer): (Iterator[A], Randomizer) = shrinker(value, rnd)
    override def canonicals(rnd: Randomizer): (Iterator[A], Randomizer) = g.canonicals(rnd)
  }

  protected def withChunkShrinker[A](g: Generator[Chunk[A]]): Generator[Chunk[A]] =
    withShrinker(g) { (c, rnd) =>
      if (c.isEmpty) (Iterator.empty, rnd)
      else {
        // TODO This would be better as a lazily computed iterator
        def loop(c: Chunk[A]): List[Chunk[A]] =
          if (c.isEmpty) Nil
          else {
            val c2 = c.take(c.size / 2)
            c2 :: loop(c2)
          }
        (loop(c).iterator, rnd)
      }
    }

  implicit def unspecializedChunkGenerator[A](implicit A: Generator[A]): Generator[Chunk[A]] =
    withChunkShrinker(
      frequency(
        1 -> specificValue(Chunk.empty[A]),
        5 -> A.map(Chunk.singleton),
        10 -> vectors[A].havingSizesBetween(0, 20).map(Chunk.vector),
        10 -> lists[A].havingSizesBetween(0, 20).map(Chunk.seq),
        10 -> lists[A]
          .havingSizesBetween(0, 20)
          .map(as => Chunk.buffer(collection.mutable.Buffer.empty ++= as)),
        10 -> lists[A]
          .havingSizesBetween(0, 20)
          .map(as => Chunk.chain(Chain.fromSeq(as))) // TODO Add variety in Chain
      ))
}

trait ChunkGeneratorsLowPriority extends ChunkGeneratorsLowPriority1 {

  implicit def chunkGenerator[A](implicit A: Generator[A], ct: ClassTag[A]): Generator[Chunk[A]] =
    withChunkShrinker(
      frequency(
        8 -> unspecializedChunkGenerator[A],
        1 -> lists[A].map(as => Chunk.array(as.toArray)),
        1 -> (for {
          as <- lists[A]
          offset <- intsBetween(0, as.size / 2)
          len <- intsBetween(0, as.size - offset)
        } yield Chunk.boxed(as.toArray, offset, len))
      ))
}

trait ChunkGenerators extends ChunkGeneratorsLowPriority {

  private def arrayChunkGenerator[A](build: (Array[A], Int, Int) => Chunk[A])(
      implicit A: Generator[A],
      ct: ClassTag[A]): Generator[Chunk[A]] =
    for {
      values <- lists[A].havingSizesBetween(0, 20).map(_.toArray)
      offset <- intsBetween(0, values.size)
      sz <- intsBetween(0, values.size - offset)
    } yield build(values, offset, sz)

  private def jbufferChunkGenerator[A, B <: JBuffer](
      build: B => Chunk[A],
      native: (Int, Array[A]) => B,
      wrap: Array[A] => B
  )(implicit A: Generator[A], cta: ClassTag[A]): Generator[Chunk[A]] =
    for {
      values <- lists[A].havingSizesBetween(0, 20).map(_.toArray)
      n = values.size
      pos <- intsBetween(0, n)
      lim <- intsBetween(pos, n)
      direct <- booleans
      bb = if (direct) native(n, values) else wrap(values)
      _ = bb.position(pos).limit(lim)
    } yield build(bb)

  val booleanArrayChunkGenerator: Generator[Chunk[Boolean]] =
    arrayChunkGenerator(Chunk.booleans _)

  val byteArrayChunkGenerator: Generator[Chunk[Byte]] =
    arrayChunkGenerator(Chunk.bytes _)

  val byteBufferChunkGenerator: Generator[Chunk[Byte]] =
    jbufferChunkGenerator[Byte, JByteBuffer](
      Chunk.byteBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n).put(values),
      JByteBuffer.wrap _
    )

  val byteVectorChunkGenerator: Generator[Chunk[Byte]] =
    for {
      values <- lists[Byte].havingSizesBetween(0, 20).map(_.toArray)
    } yield Chunk.byteVector(ByteVector.view(values))

  implicit val byteChunkGenerator: Generator[Chunk[Byte]] =
    withChunkShrinker(
      frequency(
        7 -> chunkGenerator[Byte],
        1 -> byteArrayChunkGenerator,
        1 -> byteBufferChunkGenerator,
        1 -> byteVectorChunkGenerator
      ))

  val shortArrayChunkGenerator: Generator[Chunk[Short]] =
    arrayChunkGenerator(Chunk.shorts _)

  val shortBufferChunkGenerator: Generator[Chunk[Short]] =
    jbufferChunkGenerator[Short, JShortBuffer](
      Chunk.shortBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 2).asShortBuffer.put(values),
      JShortBuffer.wrap _
    )

  implicit val shortChunkGenerator: Generator[Chunk[Short]] =
    withChunkShrinker(
      frequency(
        8 -> chunkGenerator[Short],
        1 -> shortArrayChunkGenerator,
        1 -> shortBufferChunkGenerator
      ))

  val longArrayChunkGenerator: Generator[Chunk[Long]] =
    arrayChunkGenerator(Chunk.longs _)

  val longBufferChunkGenerator: Generator[Chunk[Long]] =
    jbufferChunkGenerator[Long, JLongBuffer](
      Chunk.longBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asLongBuffer.put(values),
      JLongBuffer.wrap _
    )

  implicit val longChunkGenerator: Generator[Chunk[Long]] =
    withChunkShrinker(
      frequency(
        8 -> chunkGenerator[Long],
        1 -> longArrayChunkGenerator,
        1 -> longBufferChunkGenerator
      ))

  val intArrayChunkGenerator: Generator[Chunk[Int]] =
    arrayChunkGenerator(Chunk.ints _)

  val intBufferChunkGenerator: Generator[Chunk[Int]] =
    jbufferChunkGenerator[Int, JIntBuffer](
      Chunk.intBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asIntBuffer.put(values),
      JIntBuffer.wrap _
    )

  implicit val intChunkGenerator: Generator[Chunk[Int]] =
    withChunkShrinker(
      frequency(
        8 -> chunkGenerator[Int],
        1 -> intArrayChunkGenerator,
        1 -> intBufferChunkGenerator
      ))

  val doubleArrayChunkGenerator: Generator[Chunk[Double]] =
    arrayChunkGenerator(Chunk.doubles _)

  val doubleBufferChunkGenerator: Generator[Chunk[Double]] =
    jbufferChunkGenerator[Double, JDoubleBuffer](
      Chunk.doubleBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asDoubleBuffer.put(values),
      JDoubleBuffer.wrap _
    )

  implicit val doubleChunkGenerator: Generator[Chunk[Double]] =
    withChunkShrinker(
      frequency(
        8 -> chunkGenerator[Double],
        1 -> doubleArrayChunkGenerator,
        1 -> doubleBufferChunkGenerator
      ))

  val floatArrayChunkGenerator: Generator[Chunk[Float]] =
    arrayChunkGenerator(Chunk.floats _)

  val floatBufferChunkGenerator: Generator[Chunk[Float]] =
    jbufferChunkGenerator[Float, JFloatBuffer](
      Chunk.floatBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asFloatBuffer.put(values),
      JFloatBuffer.wrap _
    )

  implicit val floatChunkGenerator: Generator[Chunk[Float]] =
    withChunkShrinker(
      frequency(
        8 -> chunkGenerator[Float],
        1 -> floatArrayChunkGenerator,
        1 -> floatBufferChunkGenerator
      ))

  val charBufferChunkGenerator: Generator[Chunk[Char]] =
    jbufferChunkGenerator[Char, JCharBuffer](
      Chunk.charBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asCharBuffer.put(values),
      JCharBuffer.wrap _
    )

  implicit val charChunkGenerator: Generator[Chunk[Char]] =
    withChunkShrinker(
      frequency(
        9 -> chunkGenerator[Char],
        1 -> charBufferChunkGenerator
      ))
}

object ChunkGenerators extends ChunkGenerators
