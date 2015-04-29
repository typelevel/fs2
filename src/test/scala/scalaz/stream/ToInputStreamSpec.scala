package scalaz.stream

import org.scalacheck._
import Prop.{ BooleanOperators, forAll, secure, throws }

import scodec.bits.ByteVector

import scalaz.concurrent.Task

import java.io.{IOException, DataInputStream}

class ToInputStreamSpec extends Properties("toInputStream") {

  property("handles arbitrary emitAll") = forAll { bytes: List[List[Byte]] =>
    val length = bytes map { _.length } sum
    val p = Process emitAll bytes

    val dis = new DataInputStream(io.toInputStream(p map { ByteVector(_) }))
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == bytes.flatten
  }

  property("handles appended emits") = forAll { bytes: List[List[Byte]] =>
    val length = bytes map { _.length } sum
    val p = bytes map Process.emit reduceOption { _ ++ _ } getOrElse Process.empty

    val dis = new DataInputStream(io.toInputStream(p map { ByteVector(_) }))
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

    val dis = new DataInputStream(io.toInputStream(p map { ByteVector(_) }))
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

    val dis = new DataInputStream(io.toInputStream(p map { ByteVector(_) }))
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

    val dis = new DataInputStream(io.toInputStream(p map { ByteVector(_) }))
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

    val dis = new DataInputStream(io.toInputStream(p map { ByteVector(_) }))
    val buffer = new Array[Byte](length)
    dis.readFully(buffer)
    dis.close()

    List(buffer: _*) == (bytes flatMap { _.flatten })
  }

  property("invokes finalizers when terminated early") = secure {
    import Process._

    var flag = false
    val setter = Task delay { flag = true }

    val p = (emit(Array[Byte](42)) ++ emit(Array[Byte](24))) onComplete (Process eval_ setter)

    val is = io.toInputStream(p map { ByteVector view _ })

    val read = is.read()
    is.close()

    (flag == true) :| "finalizer flag" &&
      (read == 42) :| "read value"
  }

  property("safely read byte 255 as an Int") = secure {
    val p = Process emit Array[Byte](-1)
    val is = io.toInputStream(p map { ByteVector view _ })

    is.read() == 255
  }

  property("handles exceptions") = secure {
    val p: Process[Task, ByteVector] = Process eval Task.fail(new Exception())
    val is = io.toInputStream(p)
    throws(classOf[IOException])(is.read())
  }

  property("after close read should return -1") = secure {
    val p = Process emitAll Seq(Array[Byte](1, 2), Array[Byte](3, 4))
    val is = io.toInputStream(p map { ByteVector view _ })
    is.read()
    is.close()
    is.read() == -1
  }
}
