package scalaz.stream

import java.io._
import org.scalacheck._
import Prop._
import scalaz.concurrent.Task
import text._

object ReadingUTF8 extends Properties("examples.ReadingUTF8") {

  def time[A](a: => A): (A, Double) = {
    val now = System.nanoTime
    val result = a
    val millis = (System.nanoTime - now) / 1e6
    (result, millis)
  }

  def benchmark[A](descr: String, n: Int, a: => A) = {
    (0 to n).foreach { _ =>
      val (result, millis) = time(a)
      println(f"$descr%15s:$millis%12.3f\t$result")
    }
    true
  }

  val bufferSize = 4096
  //val bufferSize = 8192
  val testFile = "testdata/utf8.txt"

  val scalazIo: Task[Option[Long]] =
    Process.constant(bufferSize)
      .through(io.fileChunkR(testFile, bufferSize))
      .pipe(utf8Decode)
      .map(_.map(_.toLong).sum)
      .reduce(_ + _)
      .runLast

  val scalazIoNoUtf8: Task[Option[Long]] =
    Process.constant(bufferSize)
      .through(io.fileChunkR(testFile, bufferSize))
      .map(_.toIterable.map(_.toLong).sum)
      .reduce(_ + _)
      .runLast

  def javaIo = {
    val fileStream = new FileInputStream(testFile)
    val inputStream = new InputStreamReader(fileStream, "UTF-8")
    val bufferedReader = new BufferedReader(inputStream, bufferSize)

    val array = Array.ofDim[Char](bufferSize)
    def readChars(a: Array[Char]) = bufferedReader.read(a, 0, bufferSize)

    var sum: Long = 0
    var count = readChars(array)
    while (count >= 0) {
      val s = new String(array, 0, count)
      sum += s.map(_.toLong).sum
      count = readChars(array)
    }

    bufferedReader.close()
    inputStream.close()
    fileStream.close()

    Some(sum)
  }

  property("benchmark") = secure {
    benchmark("java.io", 10, javaIo)
    benchmark("w/o  utf8Decode", 10, scalazIoNoUtf8.run)
    benchmark("with utf8Decode", 10, scalazIo.run)
  }
}
