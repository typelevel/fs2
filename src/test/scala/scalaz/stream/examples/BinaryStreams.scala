package scalaz.stream.examples

import scalaz.concurrent.Task

import org.scalacheck._
import Prop._
import java.io.FileInputStream
import scalaz.stream._
import scalaz.stream.Process._
import scalaz.stream.Channel
import scalaz.stream.Process1
import scala.annotation.tailrec

/**
 * Sample code for various ways of handling binary streaming of data.
 */
object BinaryStreams extends Properties("binary-streams") {
  property("as a byte array chunked by 11s") = secure {
    val bufSize = 11 // 11 happens to be our times table row size

    // buf is a re-usable buffer to pass into the chunk reader. Note that use of the same buffer requires
    // unsafeChunkR which also requires no copying of the reference to the buffer (since it will be reused)
    val buf = new Array[Byte](bufSize)

    // A channel of bytes
    val chunker: Channel[Task, Array[Byte], Array[Byte]] = io.unsafeChunkR(new FileInputStream("testdata/timestable.bytes"))

    // the task to repeatedly re-use the buf buffer
    val buffer: Process[Task, Array[Byte]] = Process.eval(Task.now(buf)).repeat

    // now get a stream of the Array[Bytes] in chunks
    val stream: Process[Task, Array[Byte]] = buffer through chunker

    // convert the byte arrays to a vector of ints - for comparison with the times tables
    val timesTable: Process[Task, Vector[Int]] = stream.map { ba => ba.map(_.toInt).toVector }

    // zip with the index, so we know what the row multiplier is
    val tables = timesTable.runLog.run.zipWithIndex

    // all values should be correct for the times table
    tables.forall { case (row, rownum) =>
      val mult = rownum + 1
      row.zipWithIndex.forall { case (num1, num2) => num1 == num2 * mult}
    }
  }


  case class ByteArrayChunk(chunk: Array[Byte], complete: Boolean)

  // need a function that finds the splits within a byte array that mark the start and end of records
  // this function takes a finder function that returns an Int of the found condition index or -1 if not found
  // and uses it to create a vector of byte array chunks, the last will always be incomplete, but may be empty
  @tailrec
  def allSlices(ba: Array[Byte], f: Array[Byte] => Int, curPos:Int = 0, acc: Vector[ByteArrayChunk] = Vector.empty): Vector[ByteArrayChunk] = {
    val nextPos = if (ba.isEmpty) -1 else f(ba.tail)
    // either get the entire byte array (if no match found) or take the chunk up to the matching position
    val bac = if (nextPos < 0) ba else ba.take(nextPos + 1)
    val slices = acc :+ ByteArrayChunk(bac, nextPos >= 0)  // is it complete?
    if (nextPos < 0) slices else allSlices(ba.drop(nextPos + 1), f, nextPos + 1, slices)
  }

  // Use the allSlices function to emit all complete slices found from the splitting function, and continue
  // with the remainder of the buffered byte array to begin accumulating the next chunk to split and emit.
  // This should produce the same results regardless of buffer size.
  def splitBytesIntoRecords(f: Array[Byte] => Int): Process1[Array[Byte], Array[Byte]] = {
    def go(acc: Array[Byte]): Process1[Array[Byte], Array[Byte]] =
      await1[Array[Byte]].flatMap { (i: Array[Byte]) =>
        val slices: Vector[ByteArrayChunk] = allSlices(acc ++ i, f)
        val splits = slices.init
        val remainder = slices.last
        val isSplit = !splits.isEmpty

        if (isSplit) emitAll(splits.map(_.chunk)) fby go(remainder.chunk)
        else go(remainder.chunk)
      } orElse emit(acc)

    go(Array.empty[Byte])
  }

  def accumulateAllBytes: Process1[Array[Byte], Array[Byte]] = {
    def go(acc: Array[Byte]): Process1[Array[Byte], Array[Byte]] =
      await1[Array[Byte]].flatMap { (i: Array[Byte]) =>
        go(acc ++ i)
      } orElse emit(acc)

    go(Array.empty[Byte])
  }

  val maxCheckBufSize = 8192
  val realisticBufferSizes = Gen.choose(1,maxCheckBufSize)

  property("split by byte-value 00") = forAll(realisticBufferSizes) { bufSize =>
    try {
      val buf = new Array[Byte](bufSize)

      // A channel of bytes
      val chunker: Channel[Task, Array[Byte], Array[Byte]] = io.unsafeChunkR(new FileInputStream("testdata/timestable.bytes"))

      // the task to repeatedly re-use the buf buffer
      val buffer: Process[Task, Array[Byte]] = Process.eval(Task.now(buf)).repeat

      // now get a stream of the Array[Bytes] in chunks
      val stream: Process[Task, Array[Byte]] = buffer through chunker

      // use the splitBytesIntoRecords function to split the times table based on the 0 byte value marking the start of a new row
      val chunks: Process[Task, Array[Byte]] = stream |> splitBytesIntoRecords(_.indexOf(0))

      // convert the byte arrays to a vector of ints - for comparison with the times tables
      val timesTable: Process[Task, Vector[Int]] = chunks.collect { case ba if !ba.isEmpty => ba.map(_.toInt).toVector }

      // zip with the index, so we know what the row multiplier is
      val tables = timesTable.runLog.run.zipWithIndex

      // all values should be correct for the times table
      tables.forall { case (row, rownum) =>
        val mult = rownum + 1
        row.zipWithIndex.forall { case (num1, num2) => num1 == num2 * mult}
      }
    }
    catch {
      case ex: Throwable =>
        println("Failed on buffer size: " + bufSize)
        throw ex
    }
  }

  property("split by byte value being less than the previous value") = forAll(realisticBufferSizes) { bufSize =>
    (bufSize > 0 && bufSize <= maxCheckBufSize) ==> {
      val buf = new Array[Byte](bufSize)

      // A channel of bytes
      val chunker: Channel[Task, Array[Byte], Array[Byte]] = io.unsafeChunkR(new FileInputStream("testdata/timestable.bytes"))

      // the task to repeatedly re-use the buf buffer
      val buffer: Process[Task, Array[Byte]] = Process.eval(Task.now(buf)).repeat

      // now get a stream of the Array[Bytes] in chunks
      val stream: Process[Task, Array[Byte]] = buffer through chunker

      def findDecrease(ba: Array[Byte]): Int = {
        val foundPos = if (ba.size <= 1) -1 else ba.sliding(2).indexWhere { bytePair => bytePair(0) > bytePair(1)}
        if (foundPos < 0) -1 else foundPos + 1  // need to add one to found, to take into account the 1 lost in pairing.
      }

      // use the splitBytesIntoRecords function to split the times table based on the 0 byte value marking the start of a new row
      val chunks: Process[Task, Array[Byte]] = stream |> splitBytesIntoRecords(findDecrease)

      // convert the byte arrays to a vector of ints - for comparison with the times tables
      val timesTable: Process[Task, Vector[Int]] = chunks.collect { case ba if !ba.isEmpty => ba.map(_.toInt).toVector }

      // zip with the index, so we know what the row multiplier is
      val tables = timesTable.runLog.run.zipWithIndex

      // all values should be correct for the times table
      tables.forall { case (row, rownum) =>
        val mult = rownum + 1
        row.zipWithIndex.forall { case (num1, num2) => num1 == num2 * mult}
      }
    }
  }

  property("A binary file accumulated into one chunk") = forAll(realisticBufferSizes) { bufSize =>
    (bufSize > 0 && bufSize <= maxCheckBufSize) ==> {
      val buf = new Array[Byte](bufSize)

      val chunker: Channel[Task, Array[Byte], Array[Byte]] = io.unsafeChunkR(new FileInputStream("testdata/timestable.bytes"))

      val buffer: Process[Task, Array[Byte]] = Process.eval(Task.now(buf)).repeat

      // now get a stream of the Array[Bytes] in chunks
      val stream: Process[Task, Array[Byte]] = buffer through chunker

      // combine the chunks into one chunk
      val theOneChunk: Process[Task, Array[Byte]] = stream |> accumulateAllBytes

      val fullTable = theOneChunk.runLog.run.head

      val allCorrect = for {
        i <- 1 until 10
        j <- 0 until 10
      } yield (i * j) == fullTable((i-1) * 11 + j)

      allCorrect.forall(x => x)
    }
  }
}
