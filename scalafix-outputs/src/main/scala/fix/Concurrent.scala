/*
rule = Fs2v010Tov1
 */
package fix

import cats.effect._
import cats.implicits._
import cats.effect.concurrent._

class Concurrent[F[_]: Sync] {
  val ref: F[Ref[F, Int]] = Ref.of(1)
  Ref.of[F, Int](1)
  ref.map(_.set(1))
  ref.map(_.setAsync(1))
  val a: F[Unit] = ref.flatMap(_.update(_ + 1))
  val b: F[String] = ref.flatMap(_.modify(i => (i, "a")))
  val c: F[Boolean] = ref.flatMap(_.tryUpdate(_ + 1))
  val d: F[Option[String]] = ref.flatMap(_.tryModify(i => (i, "a")))
}