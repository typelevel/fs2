/*
rule = Fs2v010Tov1
 */
package fix

import cats.effect._
import fs2.async._ //TODO: Delete these imports and add cats.effect.concurrent._
import fs2.async.Ref
import fs2.async.refOf
import cats.implicits._

class Concurrent[F[_]: Sync] {
  val ref: F[Ref[F, Int]] = Ref(1)
  refOf[F, Int](1)
  ref.map(_.setSync(1))
  ref.map(_.setAsync(1))/* assert: Fs2v010Tov1.Removed
            ^^^^^^^^
            This got removed. Consider revisiting the implementation  */
  val a = ref.flatMap(_.modify(_ + 1))
  val b = ref.flatMap(_.modify2(i => (i, "a")))
  val c = ref.flatMap(_.tryModify(_ + 1))
  val d = ref.flatMap(_.tryModify2(i => (i, "a")))
}
