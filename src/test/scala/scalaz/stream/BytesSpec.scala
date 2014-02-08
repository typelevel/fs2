package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.{Gen, Arbitrary, Properties}
import scalaz._
import scalaz.scalacheck.ScalazProperties._
import scalaz.syntax.equal._
import scalaz.syntax.show._
import scalaz.std.list._
import scalaz.std.string._
import scalaz.std.anyVal._
import java.nio.charset.Charset


object BytesSpec extends Properties("Bytes") {


  val listEq = scalaz.std.list


  implicit val equalBytes: Equal[Bytes] = new Equal[Bytes] {
    def equal(a: Bytes, b: Bytes): Boolean = {
      a.toList === b.toList
    }
  }

  implicit val equalArrayByte: Equal[Array[Byte]] = new Equal[Array[Byte]] {
    def equal(a: Array[Byte], b: Array[Byte]): Boolean = {
      a.toList === b.toList
    }
  }


  implicit val showArrayByte: Show[Array[Byte]] = new Show[Array[Byte]] {
    override def shows(f: Array[Byte]): String = f.toList.toString()
  }

  implicit val showBytes1: Show[Bytes1] = new Show[Bytes1] {
    override def shows(f: Bytes1): String = s"Bytes1: pos=${f.pos }, sz=${f.length }, src:${f.src.show }"
  }

  implicit val showBytesN: Show[BytesN] = new Show[BytesN] {
    override def shows(f: BytesN): String = f.seg.map(_.shows).mkString("\nBytesN: (", ";\n         ", ")")
  }

  implicit val showBytes: Show[Bytes] = new Show[Bytes] {
    override def shows(f: Bytes): String = f match {
      case bs1: Bytes1 => bs1.shows
      case bsn: BytesN => bsn.shows
    }
  }

  def genBytes1(min: Int, max: Int) =
    for {
      n <- Gen.choose(min, max)
      b <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbitrary[Byte])
      from <- Gen.choose(0, (b.length - 1) max 0)
      until <- Gen.choose((from + 1) min b.length, b.length)
    } yield {
      Bytes1(b, from, until - from)
    }


  implicit val arbitraryBytes1: Arbitrary[Bytes1] = Arbitrary {
    Gen.sized { s => genBytes1(0, s) }
  }

  implicit val arbitraryBytesN: Arbitrary[BytesN] = Arbitrary {
    Gen.sized { s =>
      for {
        chunks <- Gen.choose(1, s)
        segs <- Gen.listOfN(chunks, genBytes1(1, s / chunks))
      } yield BytesN(segs.toVector)
    }
  }

  implicit val arbitraryBytes: Arbitrary[Bytes] = Arbitrary {
    Gen.oneOf(arbitraryBytes1.arbitrary, arbitraryBytesN.arbitrary)
  }

  def fullstack[A](f: => A): A = try f catch {case t: Throwable => t.printStackTrace(); throw t }


  def allBytesOps(a: Array[Byte], b: Bytes) = {
    def mapSize[A](f: Int => A): List[A] = {
      (for {i <- 0 until a.size} yield (f(i))).toList
    }

    def f(b1: Byte): Boolean = b1 % 7 == 0


    s"BytesOps: a: ${a.show } b: ${b.show }" |: all(
      ("size" |: (a.size === b.size)) &&
        ("apply" |: (mapSize(a.apply) === mapSize(b.apply))) &&
        ("head" |: (b.nonEmpty) ==> (a.head === b.head)) &&
        ("tail" |: (b.nonEmpty) ==> (a.tail === b.tail.toArray)) &&
        ("last" |: (b.nonEmpty) ==> (a.last === b.last)) &&
        ("init" |: (b.nonEmpty) ==> (a.init === b.init.toArray)) &&
        ("take" |: (mapSize(a.take(_).toList) === mapSize(b.take(_).toList))) &&
        ("takeRight" |: (mapSize(a.takeRight(_).toList) == mapSize(b.takeRight(_).toList))) &&
        ("drop" |: (mapSize(a.drop(_).toList) == mapSize(b.drop(_).toList))) &&
        ("dropRight" |: (mapSize(a.dropRight(_).toList) == mapSize(b.dropRight(_).toList))) &&
        ("takeWhile" |: (a.takeWhile(f) === b.takeWhile(f).toArray)) &&
        ("dropWhile" |: (a.dropWhile(f) === b.dropWhile(f).toArray)) &&
        ("span" |: {
          val (la, ra) = a.span(f)
          val (lb, rb) = b.span(f)
          s"la:${la.show } \n ra: ${ra.show } \n lb: ${lb.show } \n rb: ${rb.show } \n" |:
            (la === lb.toArray && ra === rb.toArray)
        }) &&
        ("splitAt" |: {
          (mapSize(a.splitAt) zip mapSize(b.splitAt)).foldLeft("init" |: true) {
            case (p, ((la, ra), (lb, rb))) =>
              p && (s"la:${la.show } \n ra: ${ra.show } \n lb: ${lb.show } \n rb: ${rb.show } \n" |:
                la === lb.toArray && ra === rb.toArray)

          }
        }) &&
        ("indexWhere" |: (a.indexWhere(f) === b.indexWhere(f))) &&
        ("indexWhere(from)" |: (mapSize(a.indexWhere(f, _)) === mapSize(b.indexWhere(f, _)))) &&
        ("lastIndexWhere" |: (a.lastIndexWhere(f) === b.lastIndexWhere(f))) &&
        ("lastIndexWhere (end)" |: (mapSize(a.lastIndexWhere(f, _)) === mapSize(b.lastIndexWhere(f, _)))) &&
        ("indexOf" |: (mapSize(i => a.indexOf(a(i))) === mapSize(i => b.indexOf(b(i))))) &&
        ("slice (left)" |: (mapSize(i => a.slice(i, a.size).toList) === mapSize(i => b.slice(i, b.size).toList))) &&
        ("slice (right)" |: (mapSize(i => a.slice(0, a.size - i).toList) === mapSize(i => b.slice(0, b.size - i).toList))) &&
        ("slice (both)" |: (mapSize(i => a.slice(i / 2, a.size - i / 2).toList) === mapSize(i => b.slice(i / 2, b.size - i / 2).toList))) &&
        ("copyToArray" |: {
          mapSize { i =>
            val aa = Array.ofDim[Byte](i)
            a.copyToArray(aa, i, a.size - i)
            a.toList
          } === mapSize { i =>
            val ba = Array.ofDim[Byte](i)
            b.copyToArray(ba, i, b.size - i)
            b.toList
          }
        }) &&
        ("forAll" |: {
          var av = List[Byte]()
          var bv = List[Byte]()
          a.foreach(b1 => av = av :+ b1)
          b.foreach(b1 => bv = bv :+ b1)
          av === bv
        })
    )
  }


  property("bytes1") = forAll { b: Bytes1 =>
    val a = b.src.drop(b.pos).take(b.length)
    allBytesOps(a, b)
  }

  property("bytesN") = forAll { b: BytesN =>
    val a = b.seg.map(_.toArray).flatten.toArray
    allBytesOps(a, b)
  }

  property("append-bytes1") = forAll {
    bl : List[Bytes1] => bl.nonEmpty ==> {
      val a = bl.map(_.toArray).flatten.toList
      val b = bl.map(_.asInstanceOf[Bytes]).reduce(_ ++ _).toArray.toList
      a === b
    }
  }

  property("append-bytesN") = forAll {
    bl : List[BytesN] => bl.nonEmpty ==> {
      val a = bl.map(_.toArray).flatten.toList
      val b = bl.map(_.asInstanceOf[Bytes]).reduce(_ ++ _).toArray.toList
      a === b
    }
  }

  property("append-bytes1-bytesN") = forAll {
    (bl1 : List[BytesN], bln: List[BytesN]) => (bl1.nonEmpty && bln.nonEmpty) ==> {
      val bl = (bl1 zip bln).map(e=>Seq(e._1,e._2)).flatten
      val a = bl.map(_.toArray).flatten.toList
      val b = bl.map(_.asInstanceOf[Bytes]).reduce(_ ++ _).toArray.toList
      a === b
    }
  }

  property("decode-bytes1") = forAll { b: Bytes1 =>
    new String(b.toArray, Charset.forName("UTF-8")) == b.decode(Charset.forName("UTF-8"))
  }

  property("decode-bytesN") = forAll { b: BytesN =>
    new String(b.toArray, Charset.forName("UTF-8")) == b.decode(Charset.forName("UTF-8"))
  }

  property("asBuffer-bytes1") = forAll { b: Bytes1 =>
    val ba = Array.ofDim[Byte](b.length)
    b.asByteBuffer.get(ba)
    val baa = b.asByteBuffers.map { bb =>
      val a = Array.ofDim[Byte](bb.remaining())
      bb.get(a)
      a
    }.flatten

    b.toArray === ba &&
      b.toArray.toList === baa.toList
  }

  property("asBuffer-bytesN") = forAll { b: BytesN =>
    val ba = Array.ofDim[Byte](b.length)
    b.asByteBuffer.get(ba)
    val baa = b.asByteBuffers.map { bb =>
      val a = Array.ofDim[Byte](bb.remaining())
      bb.get(a)
      a
    }.flatten

    b.toArray === ba &&
      b.toArray.toList === baa.toList
  }

  property("bytes-splitAt-zero-length-segment") = {
    val split: (BytesN, BytesN) = (Bytes.of("0".getBytes) ++ Bytes.of("0".getBytes)).splitAt(1)._2.splitAt(0).asInstanceOf[(BytesN, BytesN)]
    val expected = (BytesN(Vector[Bytes1]()), BytesN(Vector[Bytes1](Bytes1("0".getBytes, 0 ,1))))
    split._1.toArray === expected._1.toArray &&
      split._2.toArray === expected._2.toArray

 }

  property("monoid-laws") = monoid.laws[Bytes]
}
