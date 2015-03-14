package scalaz.stream
package examples

import org.scalacheck._
import org.scalacheck.Prop._
import scodec.bits.ByteVector

object MutableProcess1 extends Properties("examples.MutableProcess1") {

  /*
  Certain transducers like `compress.deflate`, `compress.inflate`, or
  the hashing processes in the `hash` module have a pure interface but
  use mutable objects as an implementation detail. This mutable state
  is not observable as long as these processes are consumed by the
  various `pipe`-combinators (`pipe`, `pipeO`, `pipeW`, `pipeIn`) or by
  `wye.attachL` and `wye.attachR`.

  Feeding values explicitly to these combinators (via `process1.feed`
  or `process1.feed1`) can make the mutable state observable and is
  therefore discouraged.
  */

  val bytes1 = ByteVector.view("mutable".getBytes)
  val bytes2 = ByteVector.view("state".getBytes)

  /*
  Here is an example that shows how calling `feed1` on `hash.md5`
  creates a non-referentially transparent `Process1`. `started`
  contains an instance of a mutable object that is further mutated by
  using it inside the `pipe` combinator. If `started` would be
  referentially transparent, the left- and right-hand side of the last
  expression would be equal.
  */
  property("observe mutable state") = secure {
    val started = hash.md5.feed1(bytes1)
    val bytes = Process.emit(bytes2)

    bytes.pipe(started).toList != bytes.pipe(started).toList
  }

  /*
  As already stated, using `hash.md5` inside `pipe` does not make the
  mutable state observable. In the left- and right-hand side of the last
  expression, a mutable object is instantiated for each `Process` that
  pipes values into `hash.md5`.
  */
  property("hash.md5 has a pure interface") = secure {
    val notStarted = hash.md5
    val bytes = Process(bytes1, bytes2)

    bytes.pipe(notStarted).toList == bytes.pipe(notStarted).toList
  }
}
