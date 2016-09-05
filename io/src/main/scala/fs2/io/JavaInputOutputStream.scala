package fs2
package io

import java.io.{IOException, InputStream, OutputStream}

import fs2.Chunk.Bytes
import fs2.async.mutable
import fs2.util.Async.Change
import fs2.util._
import fs2.util.syntax._

import scala.concurrent.SyncVar

private[io] object JavaInputOutputStream {
  def readBytesFromInputStream[F[_]](is: InputStream, buf: Array[Byte])(implicit F: Suspendable[F]): F[Option[Chunk[Byte]]] =
    F.delay(is.read(buf)).map { numBytes =>
      if (numBytes < 0) None
      else if (numBytes == 0) Some(Chunk.empty)
      else Some(Chunk.bytes(buf, 0, numBytes))
    }

  def readInputStreamGeneric[F[_]](fis: F[InputStream], chunkSize: Int, f: (InputStream, Array[Byte]) => F[Option[Chunk[Byte]]], closeAfterUse: Boolean = true)(implicit F: Suspendable[F]): Stream[F, Byte] = {
    val buf = new Array[Byte](chunkSize)

    def useIs(is: InputStream) =
      Stream.eval(f(is, buf))
        .repeat
        .through(pipe.unNoneTerminate)
        .flatMap(Stream.chunk)

    if (closeAfterUse)
      Stream.bracket(fis)(useIs, is => F.delay(is.close()))
    else
      Stream.eval(fis).flatMap(useIs)
  }

  def writeBytesToOutputStream[F[_]](os: OutputStream, bytes: Chunk[Byte])(implicit F: Suspendable[F]): F[Unit] =
    F.delay(os.write(bytes.toArray))

  def writeOutputStreamGeneric[F[_]](fos: F[OutputStream], closeAfterUse: Boolean, f: (OutputStream, Chunk[Byte]) => F[Unit])(implicit F: Suspendable[F]): Sink[F, Byte] = s => {
    def useOs(os: OutputStream): Stream[F, Unit] =
      s.chunks.evalMap(f(os, _))

    if (closeAfterUse)
      Stream.bracket(fos)(useOs, os => F.delay(os.close()))
    else
      Stream.eval(fos).flatMap(useOs)
  }


  def toInputStream[F[_]](implicit F:Async[F]):Pipe[F,Byte,InputStream] = {
    /** See Implementation notes at the end of this code block **/

    /** state of the upstream, we only indicate whether upstream is done and if it failed **/
    final case class UpStreamState(done:Boolean,err:Option[Throwable])
    final case class Done(rslt:Option[Throwable]) extends DownStreamState
    final case class Ready(rem:Option[Bytes]) extends DownStreamState
    sealed trait DownStreamState {  self =>
      def isDone:Boolean = self match {
        case Done(_) => true
        case _ => false
      }
    }


    /**
      * Takes source and runs it through queue, interrupting when dnState signals stream is done.
      * Note when the exception in stream is encountered the exception is emitted on the left to the queue
      * and that would be the last message enqueued.
      *
      * Emits only once, but runs in background until either source is exhausted or `interruptWhenTrue` yields to true
      */
    def processInput[F[_]](
      source:Stream[F,Byte]
      , queue:mutable.Queue[F,Either[Option[Throwable],Bytes]]
      , upState: mutable.Signal[F,UpStreamState]
      , dnState: mutable.Signal[F,DownStreamState]
    )(implicit F: Async[F]):Stream[F,Unit] = Stream.eval(F.start {
      def markUpstreamDone(result:Option[Throwable]):F[Unit] = {
        F.flatMap(upState.set(UpStreamState(done = true, err = result))) { _ =>
          queue.enqueue1(Left(result))
        }
      }


      F.flatMap(F.attempt(
        source.chunks
        .evalMap(ch => queue.enqueue1(Right(ch.toBytes)))
        .interruptWhen(dnState.discrete.map(_.isDone).filter(identity))
        .run
      )){ r => markUpstreamDone(r.left.toOption) }
    }).map(_ => ())

    /**
      * Closes the stream if not closed yet.
      * If the stream is closed, this will return once the upstream stream finishes its work and
      * releases any resources that upstream may hold.
      */
    def closeIs[F[_]](
      upState: mutable.Signal[F,UpStreamState]
      , dnState: mutable.Signal[F,DownStreamState]
    )(implicit F: Async[F]):Unit = {
      val done = new SyncVar[Attempt[Unit]]

      F.unsafeRunAsync(close(upState,dnState)) { done.put }
      done.get.fold(throw _, identity)
    }


    /**
      * Reads single chunk of bytes of size `len` into array b.
      *
      * This is implementation of InputStream#read.
      *
      * Inherently this method will block until data from the queue are available
      *
      *
      */
    def readIs[F[_]](
      dest: Array[Byte]
      , off: Int
      , len: Int
      , queue: mutable.Queue[F,Either[Option[Throwable],Bytes]]
      , dnState: mutable.Signal[F,DownStreamState]
    )(implicit F: Async[F]):Int = {
      val sync = new SyncVar[Attempt[Int]]
      F.unsafeRunAsync(readOnce[F](dest,off,len,queue,dnState)) { sync.put }
      sync.get.fold(throw _, identity)
    }

    /**
      * Reads single int value
      *
      * This is implementation of InputStream#read.
      *
      * Inherently this method will block until data from the queue are available
      *
      *
      */
    def readIs1[F[_]](
      queue: mutable.Queue[F,Either[Option[Throwable],Bytes]]
      , dnState: mutable.Signal[F,DownStreamState]
    )(implicit F: Async[F]):Int = {

      def go(acc:Array[Byte]):F[Int] = {
        F.flatMap(readOnce(acc,0,1,queue,dnState)) { read =>
          if (read < 0) F.pure(read)
          else if (read == 0) go(acc)
          else F.pure(acc(0).toInt)
        }
      }

      val sync = new SyncVar[Attempt[Int]]
      F.unsafeRunAsync(go(Array.ofDim(1)))(sync.put)
      sync.get.fold(throw _, identity)
    }


    def readOnce[F[_]](
      dest: Array[Byte]
      , off: Int
      , len: Int
      , queue: mutable.Queue[F,Either[Option[Throwable],Bytes]]
      , dnState: mutable.Signal[F,DownStreamState]
    )(implicit F: Async[F]):F[Int] = {
      // in case current state has any data available from previous read
      // this will cause the data to be acquired, state modified and chunk returned
      // won't modify state if the data cannot be acquired
      def tryGetChunk(s:DownStreamState):(DownStreamState, Option[Bytes]) = s match {
        case Done(None) =>  s -> None
        case Done(Some(err)) => s -> None
        case Ready(None) => s -> None
        case Ready(Some(bytes)) =>
          if (bytes.size <= len) Ready(None) -> Some(bytes)
          else {
            val out = bytes.take(len).toBytes
            val rem = bytes.drop(len).toBytes
            Ready(Some(rem)) -> Some(out)
          }
      }

      def setDone(rsn:Option[Throwable])(s0:DownStreamState):DownStreamState = s0 match {
        case s@Done(_) => s
        case _ => Done(rsn)
      }


      F.flatMap[(Change[DownStreamState], Option[Bytes]),Int](dnState.modify2(tryGetChunk)) {
        case (Change(o,n), Some(bytes)) =>
          F.delay {
            Array.copy(bytes.values, bytes.offset, dest,off,bytes.size)
            bytes.size
          }
        case (Change(o,n), None) =>
          n match {
            case Done(None) => F.pure(-1)
            case Done(Some(err)) => F.fail(new IOException("Stream is in failed state", err))
            case _ =>
              // Ready is guaranteed at this time to be empty
              F.flatMap(queue.dequeue1) {
                case Left(None) =>
                  F.map(dnState.modify(setDone(None)))(_ => -1) // update we are done, next read won't succeed
                case Left(Some(err)) => // update we are failed, next read won't succeed
                  F.flatMap(dnState.modify(setDone(Some(err))))(_ => F.fail[Int](new IOException("UpStream failed", err)))
                case Right(bytes) =>
                  val (copy,maybeKeep) =
                    if (bytes.size <= len) bytes -> None
                    else {
                      val out = bytes.take(len).toBytes
                      val rem = bytes.drop(len).toBytes
                      out -> Some(rem)
                    }
                  F.flatMap(F.delay {
                    Array.copy(copy.values, copy.offset, dest,off,copy.size)
                  }) { _ => maybeKeep match {
                    case Some(rem) if rem.size > 0 => F.map(dnState.set(Ready(Some(rem))))(_ => copy.size)
                    case _ => F.pure(copy.size)
                  }}
              }


          }


      }

    }


    /**
      * Closes input stream and awaits completion of the upstream
      */
    def close[F[_]](
      upState: mutable.Signal[F,UpStreamState]
      , dnState: mutable.Signal[F,DownStreamState]
    )(implicit F: Async[F]):F[Unit] = {
      F.flatMap(dnState.modify {
        case s@Done(_) => s
        case other => Done(None)
      }){ _ =>
        F.flatMap(upState.discrete.collectFirst {
          case UpStreamState(true, maybeErr) => maybeErr // await upStreamDome to yield as true
        }.runLast){ _.flatten match {
          case None => F.pure(())
          case Some(err) => F.fail[Unit](err)
        }}
      }
    }

    /*
     * Implementation note:
     *
     * We run this through 3 synchronous primitives
     *
     * - Synchronous Queue -  used to signal next available chunk, or when the upstream is done/failed
     * - UpStream signal -    used to monitor state of upstream, primarily to indicate to `close`
     *                        that upstream has finished and is safe time to terminate
     * - DownStream signal -  keeps any remainders from last `read` and signals
     *                        that downstream has been terminated that in turn kills upstream
     *
     */
    (source:Stream[F,Byte]) =>
      Stream.eval(async.synchronousQueue[F,Either[Option[Throwable],Bytes]]).flatMap { queue =>
      Stream.eval(async.signalOf[F,UpStreamState](UpStreamState(done = false, err = None))).flatMap { upState =>
      Stream.eval(async.signalOf[F,DownStreamState](Ready(None))).flatMap { dnState =>
        processInput(source,queue,upState,dnState).map { _ =>
          new InputStream {
            override def close(): Unit = closeIs(upState,dnState)
            override def read(b: Array[Byte], off: Int, len: Int): Int = readIs(b,off,len,queue,dnState)
            def read(): Int = readIs1(queue,dnState)
          }
        }.onFinalize(close(upState,dnState))
      }}}
  }

}

