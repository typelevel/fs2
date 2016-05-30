package fs2.io

import java.lang.Thread.UncaughtExceptionHandler
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger


object TestUtil {

  def namedACG(name:String):AsynchronousChannelGroup = {
    val idx = new AtomicInteger(0)
    AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
      8
      , new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"fs2-AG-$name-${idx.incrementAndGet() }")
          t.setDaemon(true)
          t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
            def uncaughtException(t: Thread, e: Throwable): Unit = {
              println("-------------- UNHANDLED EXCEPTION ------------")
              e.printStackTrace()
            }
          })
          t
        }

      }
    )
  }



  def localAddress(port: Int) = new InetSocketAddress("localhost", port)
  val localBindAddress = localAddress(9999)



}