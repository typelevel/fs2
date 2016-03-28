package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scodec.bits.ByteVector

import scala.io.Codec
import java.nio.file.{Files, Paths}
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler, FileLock}
import java.util.concurrent.{Future, TimeUnit}

import scalaz.concurrent.Task


class NioFilesSpec extends Properties("niofiles") {

  import nio.file._

  val filename = "testdata/fahrenheit.txt"

  def getfile(str: String): Array[Byte] = {
    val is = new FileInputStream(str)
    val arr = new Array[Byte](is.available())

    try {
      if (is.read(arr) != arr.length) sys.error("Read different sized arrays")
      else arr
    }
    finally is.close()
  }

  property("chunkR can read a file") = protect {
    val bytes = Process.constant(1024).toSource
      .through(chunkR(filename)).runLog.run.reduce(_ ++ _)

    bytes == ByteVector.view(getfile(filename))
  }

  property("chunkReadBuffer completely fills buffer if not EOF") = protect {
    val src = new AsynchronousFileChannel {

      private def branch[A](dst: ByteBuffer)(result: Integer => A): A = {
        if (dst.hasRemaining()) {
          dst.put(0: Byte)
          result(1)
        }
        else result(0)
      }

      override def read(dst: ByteBuffer, position: Long): Future[Integer] = branch(dst) { len =>
        new Future[Integer] {
          override def get(): Integer = len
          override def get(timeout: Long, unit: TimeUnit): Integer = len
          override def cancel(mayInterruptIfRunning: Boolean): Boolean = false
          override def isCancelled: Boolean = false
          override def isDone: Boolean = true
        }
      }

      override def read[A](dst: ByteBuffer, position: Long, attachment: A, handler: CompletionHandler[Integer, _ >: A]): Unit =
        branch(dst) { len => handler.completed(len, attachment) }

      private var _isOpen = true
      override def isOpen: Boolean = _isOpen
      override def size(): Long = Long.MaxValue
      override def force(metaData: Boolean): Unit = ()
      override def close(): Unit = { _isOpen = false }

      override def truncate(size: Long): AsynchronousFileChannel = ???
      override def write(src: ByteBuffer, position: Long): Future[Integer] = ???
      override def write[A](src: ByteBuffer, position: Long, attachment: A, handler: CompletionHandler[Integer, _ >: A]): Unit = ???
      override def tryLock(position: Long, size: Long, shared: Boolean): FileLock = ???
      override def lock(position: Long, size: Long, shared: Boolean): Future[FileLock] = ???
      override def lock[A](position: Long, size: Long, shared: Boolean, attachment: A, handler: CompletionHandler[FileLock, _ >: A]): Unit = ???

    }

    forAll { str: String => // using Gen[String].map(_.getBytes) as a source of arrays that will fit in memory
      val length: Int = str.length

      val f = (buf: ByteVector) => Task { assert(buf.length == length, "underfilled buffer") }

      Process.emit(length).toSource
        .through(nio.file.chunkR(src))
        .to(channel lift f)
        .run.attemptRun.isRight
    }
  }

  property("linesR can read a file") = protect {
    val iostrs = io.linesR(filename)(Codec.UTF8).runLog.run.toList
    val niostrs = linesR(filename)(Codec.UTF8).runLog.run.toList

    iostrs == niostrs
  }

  property("chunkW can write a file") = protect {

    val tmpname = "testdata/tempdata.tmp"
    Process.constant(1).toSource
      .through(chunkR(filename)).to(chunkW(tmpname)).run.run

    val res = ByteVector.view(getfile(tmpname)) == ByteVector.view(getfile(filename))
    Files.delete(Paths.get(tmpname))
    res
  }

}
