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

  property("chunkR can read a file") = protect {
    val bytes = Process.constant(1024).toSource
      .through(chunkR(filename)).runLog.run.reduce(_ ++ _)

    bytes == ByteVector.view(getfile(filename))
  }

  property("linesR can read a file") = protect {
    val iostrs = io.linesR(filename)(Codec.UTF8).runLog.run.toList
    val niostrs = linesR(filename)(Codec.UTF8).runLog.run.toList

    iostrs == niostrs
  }

  property("chunkW can write a file") = protect {

    val tmpname = "testdata/tempdata.tmp"
    Process.constant(1).toSource
      .through(chunkR(filename)).to(chunkW(tmpname)).run.run

    val res = ByteVector.view(getfile(tmpname)) == ByteVector.view(getfile(filename))
    Files.delete(Paths.get(tmpname))
    res
  }

  property("directory emits directory contents") = protect {
    val content = directory("testdata").map(_.getFileName.toString).runLog.run.toSet
    val expected = Set("celsius.txt", "fahrenheit.txt", "utf8.txt", "subfolder")
    content == expected
  }

  property("directoryRecurse emits recursive directory contents") = protect {
    val content = directoryRecurse("testdata").map(_.getFileName.toString).runLog.run.toSet
    val expected = Set("celsius.txt", "fahrenheit.txt", "utf8.txt", "subfolder", "a", "b")
    content == expected
  }

  property("directoryRecurse terminates at the correct depth") = protect {
    val content = directoryRecurse(Paths.get("testdata"), false, 0).map(_.getFileName.toString).runLog.run.toSet
    val expected = directory("testdata").map(_.getFileName.toString).runLog.run.toSet
    content == expected
  }

}
