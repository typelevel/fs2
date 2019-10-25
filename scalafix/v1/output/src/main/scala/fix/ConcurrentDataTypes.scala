package fix

import cats.effect._
import cats.implicits._
import fs2._



import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import cats.effect.concurrent
import cats.effect.concurrent.{ Deferred, Ref, Semaphore }
import fs2.concurrent.{ Signal, SignallingRef }

abstract class ConcurrentDataTypes[F[_]: Effect] {
  // Ref
  val ref: F[Ref[F, Int]] = Ref.of(1)
  Ref.of[F, Int](1)
  Ref.of(1)
  ref.map(_.set(1))
  ref.map(_.setAsync(1))
  val a = ref.flatMap(_.update(_ + 1))
  val b = ref.flatMap(_.modify(i => (i, "a")))
  val c = ref.flatMap(_.tryUpdate(_ + 1))
  val d = ref.flatMap(_.tryModify(i => (i, "a")))

  // Deferred
  val e: F[Deferred[F, Int]] = Deferred[F, Int]
  val e2: F[Deferred[F, Int]] = Deferred
  val f: F[Deferred[F, Int]] = promise[F, Int]
  val f2: F[Deferred[F, Int]] = promise
  e.map(_.get)
  def scheduler: Timer[F]
  e.map(_.timeout(1.second))

  // Semaphore
  val s: F[concurrent.Semaphore[F]] = cats.effect.concurrent.Semaphore(1)
  Semaphore(2)

  // Signal
  val sig: Signal[F, Int] = Signal.constant[F, Int](1)
  val sigRef: F[SignallingRef[F, Int]] = SignallingRef(1)

  // Queue
  val q: F[fs2.concurrent.Queue[F, Int]] = fs2.concurrent.Queue.unbounded

  // Topic
  val t: F[fs2.concurrent.Topic[F, Int]] = fs2.concurrent.Topic(1)
}