/*
rule = v1
 */
package fix

import cats.effect._
import cats.implicits._
import fs2._
import fs2.async.mutable.{Queue, Semaphore, Signal, Topic}
import fs2.async.{Ref, refOf, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

abstract class ConcurrentDataTypes[F[_]: Effect] {
  // Ref
  val ref: F[Ref[F, Int]] = Ref(1)
  refOf[F, Int](1)
  refOf(1)
  ref.map(_.setSync(1))
  ref.map(_.setAsync(1))/* assert: v1.Removed
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

  // Semaphore
  val s: F[mutable.Semaphore[F]] = fs2.async.mutable.Semaphore(1)
  Semaphore(2)

  // Signal
  val sig: fs2.async.immutable.Signal[F, Int] = Signal.constant[F, Int](1)
  val sigRef: F[Signal[F, Int]] = Signal(1)

  // Queue
  val q: F[Queue[F, Int]] = Queue.unbounded

  // Topic
  val t: F[Topic[F, Int]] = Topic(1)
}
