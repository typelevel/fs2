package fix

import cats.effect._
import fs2.async._ //TODO: Delete these imports and add cats.effect.concurrent._
import fs2.async.Ref
import fs2.async.refOf
import cats.implicits._

class Concurrent[F[_]: Sync] {
  val ref: F[Ref[F, Int]] = Ref.of(1)
  Ref.of[F, Int](1)
  ref.map(_.set(1))
  ref.map(_.setAsync(1))
  val a = ref.flatMap(_.update(_ + 1))
  val b = ref.flatMap(_.modify(i => (i, "a")))
  val c = ref.flatMap(_.tryUpdate(_ + 1))
  val d = ref.flatMap(_.tryModify(i => (i, "a")))
}