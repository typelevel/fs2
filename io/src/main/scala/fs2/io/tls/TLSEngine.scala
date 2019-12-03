// package fs2
// package io
// package tls

// import javax.net.ssl.SSLEngine

// import cats.{Applicative, Monad}
// import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
// import cats.effect.concurrent.{Ref, Semaphore}
// import cats.syntax.all._

// import TLSEngine.{DecryptResult, EncryptResult}

// trait TLSEngine[F[_]] {
//   /**
//     * Starts the SSL Handshake.
//     */
//   def startHandshake: F[Unit]

//   /**
//     * Signals that there will be no more data to be encrypted.
//     */
//   def stopEncrypt: F[Unit]

//   /**
//     * Signals that there will be no more data received from the network.
//     */
//   def stopDecrypt: F[Unit]

//   /**
//     * Used to encrypt data send from the application. This will also send data to the remote porty when completed.
//     *
//     * Yields to false, in case this Engine was closed for any `encrypt` operation.
//     * Otherwise this yields to true
//     *
//     * @param data   Data to encrypt
//     */
//   def encrypt(data: Chunk[Byte]): F[EncryptResult[F]]

//   /**
//     * Consumes received data from the network. User is required to consult this whenever new data
//     * from the network are available.
//     *
//     * Yields to false, if this engine is closed and no more data will be produced.
//     *
//     * @param data data received from the network.
//     */
//   def decrypt(data: Chunk[Byte]): F[DecryptResult[F]]
// }

// object TLSEngine {
//   sealed trait EncryptResult[F[_]]

//   object EncryptResult {
//     /** SSL Engine is closed **/
//     case class Closed[F[_]]() extends EncryptResult[F]

//     /** Result of the encryption **/
//     case class Encrypted[F[_]](data: Chunk[Byte]) extends EncryptResult[F]

//     /**
//       * During handshake requires hsData to be sent,
//       * then, `next` will provide encrypted data
//       * @param data     data to be sent as a result of the handshake
//       * @param next     Evaluates to next step to take during handshake process.
//       *                 When this evaluates to Encrypted() again, the handshake is complete.
//       */
//     case class Handshake[F[_]](data: Chunk[Byte], next: F[EncryptResult[F]])
//         extends EncryptResult[F]
//   }

//   sealed trait DecryptResult[F[_]]

//   object DecryptResult {
//     /**
//       * SSL engine is closed.
//       *
//       * The close frame can come together with some remaining user data.
//       *
//       * @param data The remaining data from the socket.
//       */
//     case class Closed[F[_]](
//         data: Chunk[Byte]
//     ) extends DecryptResult[F]

//     /** gives decrypted data from the network **/
//     case class Decrypted[F[_]](data: Chunk[Byte]) extends DecryptResult[F]

//     /**
//       * During handshake contains data to be sent to other party.
//       * Decrypted data will be available after handshake completes with
//       * more successful reads to occur from the remote party.
//       *
//       * @param data          Data to be sent back to network, during handshake. If empty, user must perform another
//       *                      read, before handshake's next step is to be completed and data shall be send to
//       *                      remote side.
//       * @param signalSent    When nonempty, shall be consulted to obtain next step in the handshake process
//       */
//     case class Handshake[F[_]](data: Chunk[Byte], signalSent: Option[F[DecryptResult[F]]])
//         extends DecryptResult[F]
//   }

//   /**
//     * Creates an TLS engine
//     * @param sslEngine underlying java engine
//     * @param blocker used for blocking SSL operations in the underlying SSLEngine
//     * @tparam F
//     * @return
//     */
//   def apply[F[_]: Concurrent: ContextShift](
//       sslEngine: SSLEngine,
//       blocker: Blocker
//   ): F[TLSEngine[F]] =
//     for {
//       hasWrapLock <- Ref.of(false)
//       wrapSem <- Semaphore[F](1)
//       unwrapSem <- Semaphore[F](1)
//       wrapEngine <- Wrap[F](sslEngine, blocker)
//       unwrapEngine <- Unwrap[F](sslEngine, blocker)
//     } yield new TLSEngine[F] {
//       def startHandshake: F[Unit] =
//         Sync[F].delay { sslEngine.beginHandshake() }

//       def stopEncrypt =
//         Sync[F].delay { sslEngine.closeOutbound() }

//       def stopDecrypt =
//         Sync[F].delay { sslEngine.closeInbound() }

//       def encrypt(data: Chunk[Byte]): F[EncryptResult[F]] =
//         TLSEngine.wrap(data, wrapEngine, wrapSem)

//       def decrypt(data: Chunk[Byte]): F[DecryptResult[F]] =
//         unwrapSem.withPermit(
//           TLSEngine.unwrap(data, wrapEngine, unwrapEngine, wrapSem, hasWrapLock)
//         )

//       override def toString = s"TLSEngine[$sslEngine]"
//     }

//   def wrap[F[_]: Sync](
//       data: Chunk[Byte],
//       wrapEngine: Wrap[F],
//       wrapSem: Semaphore[F]
//   ): F[EncryptResult[F]] =
//     wrapSem.withPermit {
//       def go(data: Chunk[Byte]): F[EncryptResult[F]] =
//         wrapEngine.wrap(data).map { result =>
//           if (result.closed) EncryptResult.Closed()
//           else {
//             result.awaitAfterSend match {
//               case None => EncryptResult.Encrypted(result.out)
//               case Some(await) =>
//                 EncryptResult.Handshake(result.out, await >> go(Chunk.empty))
//             }
//           }
//         }

//       go(data)
//     }

//   def unwrap[F[_]: Monad](
//       data: Chunk[Byte],
//       wrapEngine: Wrap[F],
//       unwrapEngine: Unwrap[F],
//       wrapSem: Semaphore[F],
//       hasWrapLock: Ref[F, Boolean]
//   ): F[DecryptResult[F]] = {
//     // releases wrap lock, if previously acquired
//     def releaseWrapLock: F[Unit] =
//       wrapEngine.awaitsHandshake.flatMap { awaitsHandshake =>
//         (if (awaitsHandshake) wrapEngine.handshakeComplete else Applicative[F].unit).flatMap { _ =>
//           hasWrapLock.modify(prev => (false, prev)).flatMap { prev =>
//             if (prev) wrapSem.release
//             else Applicative[F].unit
//           }
//         }
//       }

//     unwrapEngine.unwrap(data).flatMap { result =>
//       if (result.closed) Applicative[F].pure(DecryptResult.Closed(result.out))
//       else if (result.needWrap) {
//         // During handshaking we need to acquire wrap lock
//         // The wrap lock may be acquired by either of
//         // - The semaphore is decremented successfully
//         // - The wrap`s awaitsHandshake yields to true
//         // The wrap lock is released only after the hadshake will enter to `finished` state
//         // as such, the subsequent calls to `acquireWrapLock` may not actually consult semaphore.

//         def acquireWrapLock: F[Unit] =
//           hasWrapLock.get.flatMap { acquiredAlready =>
//             if (acquiredAlready) Applicative[F].unit
//             else {
//               wrapSem.tryAcquire.flatMap { acquired =>
//                 if (acquired) hasWrapLock.update(_ => true).void
//                 else
//                   wrapEngine.awaitsHandshake.flatMap { awaitsHandshake =>
//                     if (awaitsHandshake) Applicative[F].unit
//                     else acquireWrapLock
//                   }
//               }
//             }
//           }

//         acquireWrapLock.flatMap { _ =>
//           unwrapEngine.wrapHandshake.flatMap { result =>
//             def finishHandshake =
//               if (!result.finished) None
//               else
//                 Some(releaseWrapLock.flatMap { _ =>
//                   unwrap(Chunk.empty, wrapEngine, unwrapEngine, wrapSem, hasWrapLock)
//                 })
//             if (result.closed) releaseWrapLock.as(DecryptResult.Closed(Chunk.empty))
//             else Applicative[F].pure(DecryptResult.Handshake(result.send, finishHandshake))
//           }
//         }
//       } else if (result.finished) {
//         releaseWrapLock >>
//           unwrap(Chunk.empty, wrapEngine, unwrapEngine, wrapSem, hasWrapLock).map {
//             case DecryptResult.Decrypted(data) =>
//               DecryptResult.Decrypted(Chunk.concatBytes(List(result.out, data)))
//             case otherResult => otherResult
//           }
//       } else if (result.handshaking && result.out.isEmpty) {
//         // special case when during handshaking we did not get enough data to proceed further.
//         // as such, we signal this by sending an empty Handshake output.
//         // this will signal to user to perfrom more read at this stage
//         Applicative[F].pure(DecryptResult.Handshake(Chunk.empty, None))
//       } else {
//         Applicative[F].pure(DecryptResult.Decrypted(result.out))
//       }
//     }
//   }
// }
