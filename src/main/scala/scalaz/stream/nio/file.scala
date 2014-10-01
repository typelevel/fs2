package scalaz.stream
package nio

import scalaz.concurrent.Task
import scalaz.{-\/, \/-}
import scalaz.stream.Process.{emit, suspend}
import scalaz.stream.Cause.{Error, Terminated, End}

import scodec.bits.ByteVector

import scala.io.Codec

import java.nio.channels.{CompletionHandler, AsynchronousFileChannel}
import java.nio.{CharBuffer, ByteBuffer}
import java.nio.file.{StandardOpenOption, Path, Paths}
import java.net.URI


object file {

  import io.resource

  /**
   * Creates a `Channel[Task,Int,ByteVector]` from an `AsynchronousFileChannel`
   * opened for the provided path by repeatedly requesting the given number of
   * bytes. The last chunk may be less than the requested size.
   *
   * This implementation closes the `AsynchronousFileChannel` when finished
   * or in the event of an error.
   */
  def chunkR(path: String): Channel[Task, Int, ByteVector] = chunkR(Paths.get(path))

  /**
   * Creates a `Channel[Task,Int,ByteVector]` from an `AsynchronousFileChannel`
   * opened for the provided `URI` by repeatedly requesting the given number of
   * bytes. The last chunk may be less than the requested size.
   *
   * This implementation closes the `AsynchronousFileChannel` when finished
   * or in the event of an error.
   */
  def chunkR(uri: URI): Channel[Task, Int, ByteVector] = chunkR(Paths.get(uri))

  /**
   * Creates a `Channel[Task,Int,ByteVector]` from an `AsynchronousFileChannel`
   * opened for the provided `Path` by repeatedly requesting the given number of
   * bytes. The last chunk may be less than the requested size.
   *
   * This implementation closes the `AsynchronousFileChannel` when finished
   * or in the event of an error.
   */
  def chunkR(path: Path): Channel[Task, Int, ByteVector] =
    chunkR(AsynchronousFileChannel.open(path, StandardOpenOption.READ))

  /**
   * Creates a `Channel[Task,Int,ByteVector]` from an `AsynchronousFileChannel`
   * by repeatedly requesting the given number of bytes. The last chunk
   * may be less than the requested size.
   *
   * This implementation closes the `AsynchronousFileChannel` when finished
   * or in the event of an error.
   */
  def chunkR(src: => AsynchronousFileChannel): Channel[Task, Int, ByteVector] =
    chunkReadBuffer(src).mapOut(ByteVector.view)

  /**
   * Creates a `Sink[Task, ByteVector]` representation of an `AsynchronousFileChannel`,
   * creating the file if necessary, which will be closed
   * when this `Process` is halted.
   */
  def chunkW(path: String): Sink[Task, ByteVector] = chunkW(path, true)

  /**
   * Creates a `Sink[Task, ByteVector]` representation of an `AsynchronousFileChannel`
   * which will be closed when this `Process` is halted.
   */
  def chunkW(path: String, create: Boolean): Sink[Task, ByteVector] = chunkW(Paths.get(path), create)

  /**
   * Creates a `Sink[Task, ByteVector]` representation of an `AsynchronousFileChannel`,
   * creating the file if necessary, which will be closed
   * when this `Process` is halted.
   */
  def chunkW(uri: URI): Sink[Task, ByteVector] = chunkW(uri, true)

  /**
   * Creates a `Sink[Task, ByteVector]` representation of an `AsynchronousFileChannel`
   * which will be closed when this `Process` is halted.
   */
  def chunkW(uri: URI, create: Boolean): Sink[Task, ByteVector] = chunkW(Paths.get(uri), create)

  /**
   * Creates a `Sink[Task, ByteVector]` representation of an `AsynchronousFileChannel`,
   * creating the file at the specified `Path` if necessary, which will be closed
   * when this `Process` is halted.
   */
  def chunkW(path: Path): Sink[Task, ByteVector] = chunkW(path, true)

  /**
   * Creates a `Sink[Task, ByteVector]` representation of an `AsynchronousFileChannel`
   * at the specified `Path` which will be closed when this `Process` is halted.
   */
  def chunkW(path: Path, create: Boolean): Sink[Task, ByteVector] = {
    if (create) chunkW(AsynchronousFileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE))
    else chunkW(AsynchronousFileChannel.open(path, StandardOpenOption.WRITE))
  }

  /**
   * Creates a `Sink[Task, ByteVector]` representation of the `AsynchronousFileChannel`
   * which will be closed when this `Process` is halted.
   */
  def chunkW(src: => AsynchronousFileChannel): Sink[Task, ByteVector] =
    chunkWriteBuffer(src).map{ buffToTask =>
      vec: ByteVector => buffToTask(vec.toByteBuffer)
    }

  /**
   * Creates a `Process[Task,String]` from the lines of a file, using
   * the nio2 framework to make the process asynchronous. The
   * `resource` combinator is used to ensure the AsynchronousFileChannel
   * is automatically closed on completion.
   */
  def linesR(filename: String)(implicit codec: Codec): Process[Task,String] =
    linesR(Paths.get(filename))(codec)

  /**
   * Creates a `Process[Task,String]` from the lines of a file, using
   * the nio2 `AsynchronousFileChannel` to make the process asynchronous. The
   * `resource` combinator is used to ensure the `AsynchronousFileChannel`
   * is automatically closed on completion.
   */
  def linesR(path: Path)(implicit codec: Codec): Process[Task,String] =
    linesR(AsynchronousFileChannel.open(path))(codec)

  /**
   * Creates a `Process[Task,String]` from the lines of the `AsynchronousFileChannel`
   * using the nio2 framework to make the process asynchronous. The
   * `resource` combinator is used to ensure the AsynchronousFileChannel
   * is automatically closed on completion.
   */
  def linesR(src: => AsynchronousFileChannel)(implicit codec: Codec): Process[Task,String] =
    textR(src)(codec).pipe(text.lines(-1))

  /**
   * Creates a `Process[Task,String]` from the text of the `AsynchronousFileChannel`
   * using the nio2 framework to make the process asynchronous. The
   * `resource` combinator is used to ensure the AsynchronousFileChannel
   * is automatically closed on completion.
   */
  def textR(src: => AsynchronousFileChannel)(implicit codec: Codec): Process[Task,String] = suspend {
    val f = decodeByteBuff(codec)
    Process.constant(1024)
      .toSource
      .through(chunkReadBuffer(src)).flatMap { buff =>
        val charbuff = f(buff)
        val res= CharBuffer.wrap(charbuff).toString
        emit(res)
      }
  }

  /**
   * Creates a `Channel[Task,Int,ByteBuffer]` from an `AsynchronousFileChannel`
   * by repeatedly requesting the given number of bytes. The last chunk
   * may be less than the requested size. The emitted `ByteBuffer` isn't reused.
   *
   * This implementation closes the `AsynchronousFileChannel` when finished
   * or in the event of an error.
   */
  def chunkReadBuffer(src: => AsynchronousFileChannel): Channel[Task,Int,ByteBuffer] = {
    var pos = 0
    resource(Task.delay(src))(
      src => Task.delay(src.close)) { src =>
      Task.now { (size: Int) => Task.async[ByteBuffer] { cb =>
        val buff = ByteBuffer.allocate(size)
        src.read(buff, pos, null, new CompletionHandler[Integer, Null] {
          override def completed(result: Integer, attachment: Null): Unit = {
            if (result == -1) cb(-\/(Terminated(End))) // finished
            else {
              pos += result
              buff.flip()
              cb(\/-(buff))
            }
          }

          override def failed(exc: Throwable, attachment: Null): Unit =
            cb(-\/(Terminated(Error(exc))))
        })
      }}
    }
  }

  /**
   * Creates a `Sink[Task,ByteBuffer]` from an `AsynchronousFileChannel`,
   * which will be closed when this `Process` is halted.
   */
  def chunkWriteBuffer(src: => AsynchronousFileChannel): Sink[Task, ByteBuffer] = {
    var pos = 0
    resource(Task.delay(src))(
      src => Task.delay(src.close)) { src =>
      Task.now { (buff: ByteBuffer) =>
        Task.async[Unit] { cb =>
          def go(): Unit = {
            src.write(buff, pos, null, new CompletionHandler[Integer, Null] {
              override def completed(result: Integer, attachment: Null): Unit = {
                if (result == -1) cb(-\/(Terminated(End))) // finished
                else {
                  pos += result
                  if (buff.remaining() != 0) go()
                  else cb(\/-(()))
                }
              }
              override def failed(exc: Throwable, attachment: Null): Unit =
                cb(-\/(Terminated(Error(exc))))
            })
          }
          go()
        }
      }
    }
  }

  // Helper that creates a text decoder.
  private def decodeByteBuff(codec: Codec): ByteBuffer => CharBuffer = {
    var currentBuff: ByteBuffer = null
    val dec = codec.decoder

    def concat(buff: ByteBuffer) {
      if (currentBuff == null) currentBuff = buff
      else {
        val newbuff = ByteBuffer.allocate(currentBuff.remaining() + buff.remaining())
        newbuff.put(currentBuff).put(buff).flip()
        currentBuff = newbuff
      }
    }

    buff => {
      concat(buff)
      val str = dec.decode(currentBuff)
      if (currentBuff.remaining() == 0) currentBuff = null  // don't hold on to ref
      str
    }
  }
}