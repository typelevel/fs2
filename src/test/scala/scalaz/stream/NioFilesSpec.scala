package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._

import scodec.bits.ByteVector

import scala.io.Codec
import java.nio.file.{Paths, Files}
import java.io.FileInputStream


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

  property("chunkR can read a file") = secure {
    val bytes = Process.constant(1024).toSource
      .through(chunkR(filename)).runLog.run.reduce(_ ++ _)

    bytes == ByteVector.view(getfile(filename))
  }

  property("linesR can read a file") = secure {
    val iostrs = io.linesR(filename)(Codec.UTF8).runLog.run.toList
    val niostrs = linesR(filename)(Codec.UTF8).runLog.run.toList

    iostrs == niostrs
  }

  property("chunkW can write a file") = secure {

    val tmpname = "testdata/tempdata.tmp"
    Process.constant(1).toSource
      .through(chunkR(filename)).to(chunkW(tmpname)).run.run

    val res = ByteVector.view(getfile(tmpname)) == ByteVector.view(getfile(filename))
    Files.delete(Paths.get(tmpname))
    res
  }

}
