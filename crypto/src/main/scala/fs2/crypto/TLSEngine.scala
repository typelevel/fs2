package fs2.crypto

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult
import javax.net.{ssl => jns}

import fs2._
import fs2.util.Async.Ref
import fs2.util._
import fs2.util.syntax._



/**
  * Helper to establish asynchronous interface around [[jns.SSLEngine]]
  *
  * Please note that all operations here are not safe to be invoked concurrently.
  * User of this interface must assure that any wrapXXX or unwrapXX are not called concurrently.
  *
  */
trait TLSEngine[F[_]] {

  /**
    * Starts the SSL Handshake.
    * @return
    */
  def startHandshake: F[Unit]

  /**
    * Wraps presented source buffer to network Buffer.
    *
    * If the engine needs to perform any asynchronous operations, these are performed before resulting `F` finishes.
    *
    * User of this API shall assure that no concurrent `wrap` operations are executed
    *
    *
    * @param bytes  Unsecured application data
    * @return
    */
  def wrap(bytes: Chunk[Byte]): F[TLSEngine.Result]


  /**
    * Unwraps the data received from the network to data expected from application
    *
    * If the engine needs to perform any asynchronous operations, these are performed before resulting `F` finishes.
    *
    * User of this API shall assure that no concurrent `unwrap` operations are executed
    *
    * @param bytes        Secure data received over the wire
    * @return
    */
  def unwrap(bytes: Chunk[Byte]): F[TLSEngine.Result]

  /** Inbound stream (from remote party) is done, ndo no more data will be received **/
  def closeInbound: F[Unit]

  /** Outbound stream (to remote party) is done, and no more data will be sent **/
  def closeOutbound: F[Unit]

  /** available space in wrap buffer **/
  def wrapAvailable: F[Int]

  /** available space in unwrap buffer **/
  def unwrapAvailable: F[Int]

}

object TLSEngine {

  object MoreData extends Enumeration {
    val WRAP    // perform wrap, send any outstanding bytes
    , UNWRAP    // perform unwrap
    , RECEIVE_UNWRAP // receive more bytes, then unwrap
    = Value
  }

  /**
    * Result of jns.SSLEngine Wrap/UnWrap operation
    *
    * @param output       Produced data as result of the operation. This may be empty.
    *                     For the `wrap` operation, this indicates bytes that has to be sent to remote party (encrypted),
    *                     for the `unwrap` operation, this indicates bytes read from the remote party (decrypted)
    *
    *                     Note that these are bytes of the result of an active operation, so for example when this
    *                     is returned from `UNWRAP` and handshake is set to `WRAP`, then this contains result of `UNWRAP` operation.
    *
    *
    * @param handshake    If there is handshake in the process, this indicates that handshake has to be executed.
    *                     Only one side at time
    *
    * @param closed       If true, then the indication of closure of the connection has been received
    */
  case class Result(
    output: Chunk[Byte]
    , handshake: Option[MoreData.Value]
    , closed: Boolean
  )



  /**
    * Constructs new TLS Engine from java SSLEngine
    */
  def apply[F[_]](
    engine: jns.SSLEngine
    , appBufferSize: Int = 16*1024
  )(
    implicit
    F: Async[F]
    , S: Strategy
  ): F[TLSEngine[F]] = {

    def mkWrapBuffers: (ByteBuffer, ByteBuffer) =  {
      ByteBuffer.allocate(appBufferSize) ->
        ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
    }

    def mkUnWrapBuffers: (ByteBuffer, ByteBuffer) =  {
      ByteBuffer.allocate(engine.getSession.getPacketBufferSize) ->
        ByteBuffer.allocate(appBufferSize)
    }

    F.refOf(mkWrapBuffers) flatMap { wrapBuffers =>
    F.refOf(mkUnWrapBuffers) map { unwrapBuffers =>

      new TLSEngine[F] {
        def startHandshake: F[Unit] = F.delay {
          engine.beginHandshake()
        }

        def wrap(bytes: Chunk[Byte]): F[Result] =
          impl.wrap(engine, bytes, wrapBuffers)

        def unwrap(bytes: Chunk[Byte]): F[Result] =
          impl.unwrap(engine, bytes, unwrapBuffers)

        def closeInbound: F[Unit] = F.delay {
          engine.closeInbound()
        }

        def closeOutbound: F[Unit] = F.delay {
          engine.closeOutbound()
        }

        def wrapAvailable: F[Int] =
          wrapBuffers.get.map(_._1.remaining())

        def unwrapAvailable: F[Int] =
          unwrapBuffers.get.map(_._1.remaining())

      }
    }}
  }

  private[crypto] object impl {


    val EmptyBytes: Chunk[Byte] = Chunk.bytes(Array.emptyByteArray, 0, 0)


    object EngineOpName extends Enumeration {
      val WRAP, UNWRAP = Value
    }

    /**
      * Perform `wrap` operation on engine.
      *
      * Note that apart of performing the wrap, this handles following:
      *
      * - acquires wrap lock
      * - if the wrap resulted in NEED_TASK, then tha task i executed with supplied `S` strategy
      * - If the buffer UNDER/OVERFLOW is signalled new destination buffer is allocated and returned
      *
      *
      * As the last operation this releases the acquired wrap lock, to prevent two concurrent wraps to be executed.
      *
      * @param engine     SSL Engine this operates on
      * @param bytes      Bytes to wrap
      * @param buffers    Contains reference to active buffers used to perform I/O.
      *                   The first buffer is buffer with application data. Second buffer is buffer with encrypted data.
      *                   Second buffer is always empty, when this finishes, while first buffer may contain data to be
      *                   used at next invocation of wrap.
      */
    def wrap[F[_]](
      engine: jns.SSLEngine
      , bytes: Chunk[Byte]
      , buffers: Ref[F, (ByteBuffer, ByteBuffer)]
    )(implicit F: Async[F], S: Strategy): F[Result] = {
      import  SSLEngineResult.Status._
      import SSLEngineResult.HandshakeStatus._

      def go(input: ByteBuffer, output: ByteBuffer): F[Result] = {

        def done = complete(buffers, input, output) _

        F.delay { engine.wrap(input, output) } flatMap { result =>
          result.getStatus match {
            case OK =>
              result.getHandshakeStatus match {
                case NOT_HANDSHAKING =>
                  // at wrap operation this indicates all data were processed and lets get the result
                  done(None)

                case NEED_TASK =>
                  // this needs to run any outstanding async  tasks and repeat command with last status of bytes.
                  F.flatMap(runTasks(engine))(_ => go(input, output))

                case NEED_UNWRAP =>
                  done(Some(MoreData.UNWRAP))

                case NEED_WRAP =>
                  done(Some(MoreData.WRAP))

                case FINISHED =>
                  done(None)
              }

            case BUFFER_OVERFLOW =>
              // indicates we need to consume data in buffer.
              // that leads to requirement to return and indicate this as the next op
              done(Some(MoreData.WRAP))

            case BUFFER_UNDERFLOW =>
              F.fail(new Throwable("BUFFER_UNDERFLOW at wrap not possible"))

            case CLOSED =>
              // this may indicate end of stream, but only if there were no data produced
              // for the wrap, this likely won't be ever the case
              done(None) map { r =>
                if (r.output.isEmpty) r.copy(closed = true)
                else r
              }

          }


        }
      }

      buffers.get.flatMap { case (origInput, origOutput) =>
        go(fillBuffer(bytes, origInput), origOutput)
      }


    }



    /**
      * Perform `un-wrap` operation on engine.
      *
      * Note that apart of performing the un-wrap, this handles following:
      *
      * - acquires un-wrap lock
      * - if the un-wrap resulted in NEED_TASK, then tha task i executed with supplied `S` strategy
      * - If the buffer UNDERFLOW is returned then we memoize the bytes with signal received and request for more bytes to input
      * - If the buffer OVERFLOW
      *
      * As the last operation this releases the acquired lock to prevent concurrent unwraps to be executed simultaneously.
      *
      * @param engine     SSL Engine this operates on
      * @param bytes      Bytes to wrap
      * @param buffers    Contains reference to active buffers used to perform I/O.
      *                   The first buffer is buffer with encrypted data. Second buffer is buffer with decrypted data.
      *                   Second buffer is always empty, when this finishes, while first buffer may contain data to be
      *                   used at next invocation of unwrap.
      *
      */
    def unwrap[F[_]](
      engine: jns.SSLEngine
      , bytes: Chunk[Byte]
      , buffers: Ref[F, (ByteBuffer, ByteBuffer)]
    )(implicit F: Async[F], S: Strategy): F[Result] = {
      import  SSLEngineResult.Status._
      import SSLEngineResult.HandshakeStatus._

      def go(input: ByteBuffer, output: ByteBuffer): F[Result] = {


        def done = complete(buffers, input, output) _

        F.delay { engine.unwrap(input, output) } flatMap { result =>
          result.getStatus match {
            case OK =>
              result.getHandshakeStatus match {
                case NOT_HANDSHAKING =>
                  // this indicates the we still may have to proceed some data from the input buffer.
                  // just recurse until we get BUFFER_UNDERFLOW
                  go(input, output)

                case NEED_TASK =>
                  // this needs to run any outstanding async  tasks and repeat command with last status of bytes.
                  F.flatMap(runTasks(engine))(_ => go(input, output))

                case NEED_UNWRAP =>
                  done(Some(MoreData.UNWRAP))

                case NEED_WRAP =>
                  done(Some(MoreData.WRAP))

                case FINISHED =>
                  done(None)
              }

            case BUFFER_OVERFLOW =>
              // indicates we need to consume data in buffer.
              // that leads to requirement to return and indicate this as the next op
              done(Some(MoreData.UNWRAP))

            case BUFFER_UNDERFLOW =>
              // we don't have enough data available to perform UNWRAP.
              // that means we shall read more before again performing unwrap
              done(None) map { r =>
                if (r.output.isEmpty) r.copy(handshake = Some(MoreData.RECEIVE_UNWRAP))
                else r
              }

            case CLOSED =>
              // this may indicate end of stream, but only if there were no data produced
              done(None) map { r =>
                if (r.output.isEmpty) r.copy(closed = true)
                else r
              }
          }
        }
      }

      buffers.get.flatMap { case (origInput, origOutput) =>
        go(fillBuffer(bytes, origInput), origOutput)
      }
    }


    /**
      * Completes wrap/unwrap operation to extract results and modify the output buffers.
      * @param buffers    Reference to active buffers that are used for wrap/unwrap. Each operation has different set of buffers.
      * @param next       Next action to be returned in result.
      * @param input      input buffer after wrap/unwrap
      * @param output     output buffer after wrap/unwrap
      * @tparam F
      * @return
      */
    def complete[F[_]](
      buffers: Ref[F, (ByteBuffer, ByteBuffer)]
      , input: ByteBuffer
      , output:ByteBuffer
    )(
      next: Option[MoreData.Value]
    )(implicit F: Async[F]): F[Result] = {
      // complete with compacting input buffer to be ready for eventually reading in future.
      // from the output buffer take whatever was committed to it, and then
      // put them back (both input/output) in state, so we don't have to resize them again and
      // they are properly reused.
      // also note this will always make defensive copy of output chunk of bytes.

        input.compact()

        buffers.modify(_ => (input, output)) *> F.delay {
          Result(buffer2Bytes(output), next, closed = false)
        }

    }

    /**
      * With supplied bytes fill the supplied buffer. Note that this may create new buffer, if supplied buffer is not able
      * to hold bytes supplied.
      *
      * This expected supplied buffer to be in write-ready state, while resulting buffer is in read-ready state
      *
      */
    def fillBuffer(bytes: Chunk[Byte], buffer: ByteBuffer): ByteBuffer = {
      if (bytes.isEmpty) { buffer.flip(); buffer }
      else {
        val dest =
          if (buffer.remaining() >= bytes.size)  buffer
          else {
            val bb = ByteBuffer.allocate(buffer.capacity() + bytes.size)
            buffer.flip()
            bb.put(buffer)
            bb
          }
        val bs = bytes.toBytes
        dest.put(bs.values, bs.offset, bs.size).flip()
        dest
      }
    }

    /**
      * Creates new buffer of `desired` size. Resulting buffer is ready to be written at full capacity.
      * All data in buffer are lost
      *
      * @param buffer   Buffer to resize, potentially with some data
      * @param desired  Desired size of new buffer
      * @return
      */
    def resizeBufferW(buffer: ByteBuffer, desired: Int): ByteBuffer = {
      if (buffer.capacity() >= desired) { buffer.clear(); buffer }
      else ByteBuffer.allocate(desired)
    }



    /** runs all available tasks , retruning when tasks has been finished **/
    def runTasks[F[_]](engine: jns.SSLEngine)(implicit F: Async[F], S: Strategy):F[Unit] = {
      F.delay { Option(engine.getDelegatedTask) }.flatMap {
        case None => F.pure(())
        case Some(engineTask) =>
          F.async[Unit] { cb =>
            F.delay { S {
              try { engineTask.run(); cb(Right(())) }
              catch { case t : Throwable => cb(Left(t))}
            }}
          } *> runTasks(engine)
      }
    }

    /**
      * Supplied buffer is consumed to produce bytes.
      *
      * It is expected that buffer is in write-ready state.
      *
      * Buffer is cleared to be fresh to receive new output data
      *
      * @return
      */
    def buffer2Bytes(buffer: ByteBuffer): Chunk[Byte] = {
      buffer.flip()
      val dest = Array.ofDim[Byte](buffer.remaining())
      buffer.get(dest)
      buffer.clear()
      Chunk.bytes(dest, 0, dest.length)
    }


  }

}