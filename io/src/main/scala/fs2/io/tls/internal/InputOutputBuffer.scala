package spinoco.fs2.crypto.internal

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import cats.Applicative
import javax.net.ssl.SSLEngineResult
import cats.effect.Sync
import cats.syntax.all._
import fs2.Chunk

/**
  * Buffer that wraps two Input/Output buffers together and guards
  * for input/output operations to be performed sequentially
  */
private[crypto] trait InputOutputBuffer[F[_]] {

  /**
    * Feeds the `data`. I/O Buffer must be awaiting input
    */
  def input(data: Chunk[Byte]): F[Unit]


  /**
    * Gets result from any operation. must bnot be awaiting input
    */
  def output: F[Chunk[Byte]]


  /**
    * Performs given operation. Buffer must not be awaiting input
    */
  def perform(f: (ByteBuffer, ByteBuffer) => Either[Throwable, SSLEngineResult]): F[SSLEngineResult]

  /**
    * Expands output buffer operation while copying all data eventually present in the buffer already
    * @return
    */
  def expandOutput: F[Unit]

  /** return remaining data in input during consume **/
  def inputRemains: F[Int]

}


private[crypto] object InputOutputBuffer {


  def mk[F[_] : Sync](inputSize: Int, outputSize: Int): F[InputOutputBuffer[F]] = Sync[F].delay {
    val inBuff = new AtomicReference[ByteBuffer](ByteBuffer.allocate(inputSize))
    val outBuff = new AtomicReference[ByteBuffer](ByteBuffer.allocate(outputSize))
    val awaitInput = new AtomicBoolean(true)

    new InputOutputBuffer[F] {

      def input(data: Chunk[Byte]): F[Unit] = Sync[F].suspend {
        if (awaitInput.compareAndSet(true, false)) {
          val in = inBuff.get
          val expanded =
            if (in.remaining() >= data.size) Applicative[F].pure(in)
            else expandBuffer(in, capacity => (capacity + data.size) max (capacity * 2))

          expanded.map { buff =>
            inBuff.set(buff)
            val bs = data.toBytes
            buff.put(bs.values, bs.offset, bs.size)
            buff.flip()
            outBuff.get().clear()
          } void
        } else {
          Sync[F].raiseError(new Throwable("input bytes allowed only when awaiting input"))
        }
      }

      def perform(f: (ByteBuffer, ByteBuffer) => Either[Throwable, SSLEngineResult]): F[SSLEngineResult] = Sync[F].suspend {
        if (awaitInput.get) Sync[F].raiseError(new Throwable("Perform cannot be invoked when awaiting input"))
        else {
          awaitInput.set(false)
          f(inBuff.get, outBuff.get) match {
            case Left(err) => Sync[F].raiseError(err)
            case Right(result) => Applicative[F].pure(result)
          }
        }
      }

      def output: F[Chunk[Byte]] = Sync[F].suspend {
        if (awaitInput.compareAndSet(false, true)) {
          val in = inBuff.get()
          in.compact()

          val out = outBuff.get()
          if (out.position() == 0) Applicative[F].pure(Chunk.empty)
          else {
            out.flip()
            val cap = out.limit()
            val dest = Array.ofDim[Byte](cap)
            out.get(dest)
            out.clear()
            Applicative[F].pure(Chunk.bytes(dest))
          }

        } else {
          Sync[F].raiseError(new Throwable("output bytes allowed only when not awaiting INput"))
        }
      }

      def expandBuffer(buffer: ByteBuffer, resizeTo: Int => Int): F[ByteBuffer] = Sync[F].suspend {
        val copy = Array.ofDim[Byte](buffer.position())
        val next = ByteBuffer.allocate(resizeTo(buffer.capacity()))
        buffer.flip()
        buffer.get(copy)
        Applicative[F].pure(next.put(copy))
      }

      def expandOutput: F[Unit] = {
        expandBuffer(outBuff.get, _ * 2).map(outBuff.set(_))
      }

      def inputRemains =
        Sync[F].delay {  inBuff.get().remaining() }

    }
  }



}



