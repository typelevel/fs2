package scalaz.stream

import java.io._
import org.scalacheck._
import Prop._
import process1._
import scalaz.concurrent.Task

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
      println(s"$descr:\t$millis\t$result")
    }
    true
  }

  val bufferSize = 4096
  //val bufferSize = 8192
  val testFile = "testdata/utf8.txt"

  val scalazIo: Task[Option[Long]] =
    Process.constant(bufferSize)
      .through(io.fileChunkR(testFile, bufferSize))
      .map(a => Bytes.unsafe(a))
      .pipe(utf8Decode)
      .map(_.map(_.toLong).sum)
      .reduce(_ + _)
      .runLast

  def javaIo = {
    val fileStream = new FileInputStream(testFile)
    val inputStream = new InputStreamReader(fileStream, "UTF-8")
    val bufferedReader = new BufferedReader(inputStream, bufferSize)

    val array = Array.ofDim[Char](bufferSize)
    def readChars(a: Array[Char]) = bufferedReader.read(a, 0, bufferSize)

    var l = 0
    var sum: Long = 0
    var count = readChars(array)
    while (count >= 0) {
      l += 1
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
    benchmark("scalaz", 10, scalazIo.run)
    benchmark("Java", 10, javaIo)
  }
}
