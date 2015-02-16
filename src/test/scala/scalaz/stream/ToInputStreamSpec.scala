package scalaz.stream

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task

import java.io.DataInputStream

object ToInputStreamSpec extends Properties("toInputStream") {

  property("handles arbitrary emitAll") = forAll { bytes: List[List[Byte]] =>
    val length = bytes map { _.length } sum
    val p = Process emitAll bytes

    val dis = new DataInputStream(io.toInputStream(p) { _.toArray })
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == bytes.flatten
  }

  property("handles appended emits") = forAll { bytes: List[List[Byte]] =>
    val length = bytes map { _.length } sum
    val p = bytes map Process.emit reduceOption { _ ++ _ } getOrElse Process.empty

    val dis = new DataInputStream(io.toInputStream(p) { _.toArray })
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == bytes.flatten
  }

  property("handles await") = forAll { chunk: List[Byte] =>
    val length = chunk.length

    val p = Process.await(Task now (())) { _ =>
      Process emit chunk
    }

    val dis = new DataInputStream(io.toInputStream(p) { _.toArray })
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    buffer.toList == chunk
  }

  property("handles appended awaits") = forAll { bytes: List[List[Byte]] =>
    val length = bytes map { _.length } sum

    val p = bytes map { data =>
      Process.await(Task now (())) { _ =>
        Process emit data
      }
    } reduceOption { _ ++ _ } getOrElse Process.empty

    val dis = new DataInputStream(io.toInputStream(p) { _.toArray })
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == bytes.flatten
  }

  property("handles one append within an await") = secure {
    val bytes: List[List[List[Byte]]] = List(List(), List(List(127)))
    val length = bytes map { _ map { _.length } sum } sum

    val p = bytes map { data =>
      Process.await(Task now (())) { _ =>
        data map Process.emit reduceOption { _ ++ _ } getOrElse Process.empty
      }
    } reduceOption { _ ++ _ } getOrElse Process.empty

    val dis = new DataInputStream(io.toInputStream(p) { _.toArray })
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == (bytes flatMap { _.flatten })
  }

  property("handles appends within awaits") = forAll { bytes: List[List[List[Byte]]] =>
    val length = bytes map { _ map { _.length } sum } sum

    val p = bytes map { data =>
      Process.await(Task now (())) { _ =>
        data map Process.emit reduceOption { _ ++ _ } getOrElse Process.empty
      }
    } reduceOption { _ ++ _ } getOrElse Process.empty

    val dis = new DataInputStream(io.toInputStream(p) { _.toArray })
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == (bytes flatMap { _.flatten })
  }
}