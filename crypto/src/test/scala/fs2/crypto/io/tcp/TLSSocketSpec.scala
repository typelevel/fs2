package fs2.crypto.io.tcp



import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import fs2._
import fs2.crypto.TLSEngine
import fs2.io.TestUtil
import fs2.util.Async
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.ExecutionContext

object TLSSocketSpec {


  implicit val S = Strategy.fromExecutionContext(ExecutionContext.Implicits.global)


  val sslCtx = {
    val keyStore = KeyStore.getInstance("jks")
    val keyStoreFile = getClass.getResourceAsStream("/mykeystore.jks")
    keyStore.load(keyStoreFile, "password".toCharArray )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, "pass".toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(keyStore)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, null)
    ctx
  }


}




class TLSSocketSpec extends Fs2Spec {

  import TLSSocketSpec.sslCtx

  implicit val streamByteGen: Arbitrary[Vector[String]] = Arbitrary {
    for {
      data <- implicitly[Arbitrary[List[String]]].arbitrary
      _ <- if (data.isEmpty || data.forall(_.isEmpty)) Gen.fail else Gen.const(data)
    } yield {
      data.toVector
    }
  }


  "TLSSocket" - {

    "encrypts-decrypts-data" in forAll    { data: Vector[String] =>
      import TestUtil.localTcpSocket

      val sslServerEngine = sslCtx.createSSLEngine()
      sslServerEngine.setUseClientMode(false)
      sslServerEngine.setNeedClientAuth(false)

      val sslClientEngine = sslCtx.createSSLEngine()
      sslClientEngine.setUseClientMode(true)
      sslClientEngine.setNeedClientAuth(false)

      val size = data.map(_.getBytes.length).sum

      val input: Stream[Task, Byte] = Stream.emits[Task, String](data) flatMap { s =>
        if (s.isEmpty) Stream.empty
        else  Stream.chunk(Chunk.bytes(s.getBytes))
      }


      val result =
      (Stream.eval(Async.ref[Task, Stream[Task, Byte]]) flatMap { socket2SentRef =>
      localTcpSocket[Task](Stream.eval(socket2SentRef.get) flatMap identity) flatMap { case (socket1, sentFromS1) =>
      localTcpSocket[Task](sentFromS1) flatMap { case (socket2, sentFromS2) =>
      Stream.eval(socket2SentRef.setPure(sentFromS2)) flatMap { _ =>
      Stream.eval(TLSEngine[Task](sslServerEngine)) flatMap { tlsEngine1 =>
      Stream.eval(TLSEngine[Task](sslClientEngine)) flatMap { tlsEngine2 =>
      Stream.eval(TLSSocket(socket1, tlsEngine1)) flatMap { tlsSocket1 =>
      Stream.eval(TLSSocket(socket2, tlsEngine2)) flatMap { tlsSocket2 =>

        concurrent.join(Int.MaxValue)(Stream(
          (input through tlsSocket2.writes(None)) drain
          , (tlsSocket1.reads(1024) to tlsSocket1.writes(None)) drain
          , tlsSocket2.reads(1024)
        ))

      }}}}}}}})
      .take(size).chunks
      .runLog map { allChunks =>
        val all = Chunk.concat(allChunks).toBytes
        new String(all.values, all.offset, all.size)
      }

      data.mkString shouldBe result.unsafeRun

    }


  }

}

