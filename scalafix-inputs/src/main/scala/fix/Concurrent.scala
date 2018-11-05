/*
rule = Fs2v010Tov1
 */
package fix

import cats.effect._
import fs2.async._
import cats.implicits._
import fs2.async.Ref.Change

class Concurrent[F[_]: Sync] {
  val ref: F[Ref[F, Int]] = Ref(1)
  refOf[F, Int](1)
  ref.map(_.setSync(1))
  ref.map(_.setAsync(1))/* assert: Fs2v010Tov1.Removed
            ^^^^^^^^
            This got removed. Consider revisiting the implementation  */
  val a: F[Change[Int]] = ref.flatMap(_.modify(_ + 1))
  val b: F[(Change[Int], String)] = ref.flatMap(_.modify2(i => (i, "a")))
  val c: F[Option[Change[Int]]] = ref.flatMap(_.tryModify(_ + 1))
  val d: F[Option[(Change[Int], String)]] = ref.flatMap(_.tryModify2(i => (i, "a")))
}
