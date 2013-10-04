package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scalaz.concurrent.Task
import scala.util.Random
import scalaz.stream.io._

/**
 *
 * User: pach
 * Date: 7/4/13
 * Time: 4:31 AM
 * (c) 2011-2013 Spinoco Czech Republic, a.s.
 */
object GzipSpec extends Properties("io.gzip") {

  import Process._

  property("deflateAndInflate") = secure {

    val contentChunk = Array.ofDim[Byte](1024)
    Random.nextBytes(contentChunk)

    val allContent = for {_ <- 1 to 1024} yield (contentChunk)
    
    val source =
      Process(allContent:_*).map(v => Task(v)).eval

    val deflateInflate =
      source throughOption io.deflate() through io.inflate()
    
    deflateInflate.runLog.run.map(_.toArray).reduce(_ ++ _).toSeq == allContent.reduce(_ ++ _).toSeq
  }

}
