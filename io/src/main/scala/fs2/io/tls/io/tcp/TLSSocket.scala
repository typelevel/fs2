// package spinoco.fs2.crypto.io.tcp
// import java.net.SocketAddress

// import cats.Applicative
// import cats.data.Chain
// import cats.effect.concurrent.{Ref, Semaphore}
// import javax.net.ssl.SSLEngine
// import cats.effect._
// import cats.syntax.all._
// import fs2._
// import fs2.io.tcp.Socket
// import spinoco.fs2.crypto.TLSEngine
// import spinoco.fs2.crypto.TLSEngine.{DecryptResult, EncryptResult}
// import spinoco.fs2.crypto.internal.util.concatBytes

// import scala.annotation.tailrec
// import scala.concurrent.ExecutionContext
// import scala.concurrent.duration._

// trait TLSSocket[F[_]] extends Socket[F] {

//   /** when invoked, will initiate new TLS Handshake **/
//   def startHandshake: F[Unit]

// }
// object TLSSocket {

//   @inline def apply[F[_]](implicit instance: TLSSocket[F]): TLSSocket[F] = instance
//   /**
//     * Cretes an TLS Socket
//     * @param socket   TCP Socket that will be used as transport for TLS
//     * @param engine   SSL engine from jdk
//     * @param sslEc    An Execution context, that will be used to run SSL Engine's tasks.
//     */
//   def instance[F[_] : Concurrent : ContextShift](socket: Socket[F], engine: SSLEngine, sslEc: ExecutionContext): F[TLSSocket[F]] = {
//     TLSEngine.instance(engine, sslEc) flatMap { tlsEngine =>
//       TLSSocket.instance(socket, tlsEngine)
//     }
//   }
//   /**
//     * Wraps raw tcp socket with supplied SSLEngine to form SSL Socket
//     *
//     * Note that engine will switch to handshake mode once resulting `F` is evaluated.
//     *
//     * The resulting socket will not support concurrent reads or concurrent writes
//     * (concurrently reading/writing is ok).
//     *
//     *
//     * @param socket               Raw TCP Socket
//     * @param tlsEngine            An TSLEngine to use
//     */
//   def instance[F[_]: Concurrent : ContextShift](
//     socket: Socket[F]
//     , tlsEngine: TLSEngine[F]
//   ): F[TLSSocket[F]] = {
//     Ref.of[F, Chain[Chunk[Byte]]](Chain.empty) flatMap { readBuffRef =>
//     Semaphore(1) map { readSem =>

//       /** gets that much data from the buffer if available **/
//       def getFromBuff(max: Int): F[Chunk[Byte]] = {
//         readBuffRef.modify { impl.takeFromBuff(_, max) }
//       }

//       new TLSSocket[F] { self =>

//         // During handshake this start the reader action so we may try
//         // to read data from the socket, if required.
//         // Started only on `write` thread, during handshake
//         // this resolves situation, when user wants just to write data to socket
//         // before actually reading them
//         def readHandShake(timeout: Option[FiniteDuration]): F[Unit] = {
//           readSem.acquire >>
//           Bracket[F, Throwable].guarantee(
//             read0(10240, timeout).flatMap {
//               case Some(data) if data.nonEmpty => readBuffRef.update { _ :+ data }
//               case _ => Applicative[F].unit
//             }
//           )(readSem.release)
//         }

//         // like `read` but not guarded by `read` semaphore
//         def read0(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = {
//           getFromBuff(maxBytes) flatMap { fromBuff =>
//             if (fromBuff.nonEmpty) Applicative[F].pure(Some(fromBuff): Option[Chunk[Byte]])
//             else {
//               def readLoop: F[Option[Chunk[Byte]]] = {
//                 socket.read(maxBytes, timeout) flatMap {
//                   case Some(data) =>
//                     def go(result: DecryptResult[F]): F[Option[Chunk[Byte]]] = {
//                       result match {
//                         case DecryptResult.Decrypted(data) =>
//                           if (data.size <= maxBytes) Applicative[F].pure(Some(data))
//                           else readBuffRef.update { _ :+ data.drop(maxBytes) } as Some(data.take(maxBytes))

//                         case DecryptResult.Handshake(toSend, next) =>
//                           if (toSend.isEmpty && next.isEmpty) {
//                             // handshake was not able to produce output data
//                             // as such another read is required
//                             readLoop
//                           } else {
//                             socket.write(toSend, timeout) flatMap { _ => next match {
//                               case None => readLoop
//                               case Some(next) => next flatMap go
//                             }}
//                           }
//                         case DecryptResult.Closed() => Applicative[F].pure(None)
//                       }
//                     }

//                     tlsEngine.decrypt(data) flatMap go

//                   case None => Applicative[F].pure(None)
//                 }
//               }

//               readLoop
//             }
//           }
//         }
//         def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = {
//           readSem.acquire >>
//           Bracket[F, Throwable].guarantee({
//             def go(acc: Chain[Chunk[Byte]]): F[Option[Chunk[Byte]]] = {
//               val toRead = numBytes - acc.foldLeft(0)(_ + _.size)
//               if (toRead <= 0) Applicative[F].pure(Some(concatBytes(acc)))
//               else {
//                 read0(numBytes, timeout) flatMap {
//                   case Some(chunk) => go(acc :+ chunk)
//                   case None => Applicative[F].pure(Some(concatBytes(acc)))
//                 }
//               }
//             }

//             go(Chain.empty)
//           })(readSem.release)
//         }

//         def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = {
//           readSem.acquire >>
//           Bracket[F, Throwable].guarantee(read0(maxBytes, timeout))(readSem.release)
//         }
//         def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] = {
//           def go(result: EncryptResult[F]): F[Unit] = {
//             result match {
//               case EncryptResult.Encrypted(data) => socket.write(data, timeout)

//               case EncryptResult.Handshake(data, next) =>
//                 socket.write(data, timeout) flatMap { _ => Concurrent[F].start(readHandShake(timeout)) *> next flatMap go }

//               case EncryptResult.Closed() =>
//                 Sync[F].raiseError(new Throwable("TLS Engine is closed"))
//             }
//           }

//           tlsEngine.encrypt(bytes).flatMap(go)
//         }

//         def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] =
//           Stream.repeatEval(read(maxBytes, timeout)).unNoneTerminate.flatMap(Stream.chunk)

//         def writes(timeout: Option[FiniteDuration]): Pipe[F, Byte, Unit] =
//           _.chunks.evalMap(write(_, timeout))
//         def endOfOutput: F[Unit] =
//           tlsEngine.stopEncrypt flatMap { _ => socket.endOfOutput }
//         def endOfInput: F[Unit] =
//           tlsEngine.stopDecrypt flatMap { _ => socket.endOfInput }
//         def localAddress: F[SocketAddress] =
//           socket.localAddress

//         def remoteAddress: F[SocketAddress] =
//           socket.remoteAddress

//         def startHandshake: F[Unit] =
//           tlsEngine.startHandshake

//         def close: F[Unit] =
//           tlsEngine.stopEncrypt flatMap { _ =>
//           tlsEngine.stopDecrypt flatMap { _ =>
//             socket.close
//           }}

//         def isOpen: F[Boolean] = socket.isOpen

//       }

//     }}

//   }
//   private[tcp] object impl {

//     def takeFromBuff(buff: Chain[Chunk[Byte]], max: Int): (Chain[Chunk[Byte]], Chunk[Byte]) = {
//       @tailrec
//       def go(rem: Chain[Chunk[Byte]], acc: Chain[Chunk[Byte]], toGo: Int): (Chain[Chunk[Byte]], Chunk[Byte]) = {
//         if (toGo <= 0) (rem, concatBytes(acc))
//         else {
//           rem.uncons match {
//             case Some((head, tail)) =>
//               val add = head.take(toGo)
//               val leave = head.drop(toGo)
//               if (leave.isEmpty) go(tail, acc :+ add, toGo - add.size)
//               else go(leave +: tail, acc :+ add, toGo - add.size)

//             case None =>
//               go(rem, acc, 0)

//           }
//         }
//       }

//       if (buff.isEmpty) (Chain.empty, Chunk.empty)
//       else go(buff, Chain.empty, max)
//     }

//   }

// }
