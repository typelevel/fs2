/*
rule = Fs2v010Tov1
 */
package fix

import cats.effect._
import fs2.async._
import fs2.async.Ref
import fs2.async.refOf
import cats.implicits._
import fs2._
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global

abstract class ConcurrentDataTypes[F[_]: Effect] {
  // Ref
  val ref: F[Ref[F, Int]] = Ref(1)
  refOf[F, Int](1)
  refOf(1)
  ref.map(_.setSync(1))
  ref.map(_.setAsync(1))/* assert: Fs2v010Tov1.Removed
            ^^^^^^^^
            This got removed. Consider revisiting the implementation  */
  val a = ref.flatMap(_.modify(_ + 1))
  val b = ref.flatMap(_.modify2(i => (i, "a")))
  val c = ref.flatMap(_.tryModify(_ + 1))
  val d = ref.flatMap(_.tryModify2(i => (i, "a")))

  // Deferred
  val e: F[Promise[F, Int]] = Promise.empty[F, Int]
  val e2: F[Promise[F, Int]] = Promise.empty
  val f: F[Promise[F, Int]] = promise[F, Int]
  val f2: F[Promise[F, Int]] = promise
  e.map(_.cancellableGet)
  def scheduler: Scheduler
  e.map(_.timedGet(1.second, scheduler))
}
