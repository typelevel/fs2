package fs2.internal

import java.util.concurrent.Phaser

/** Simple wrapper around `Phaser`. Like a `CountDownLatch` that can count up also. */
final class TwoWayLatch private(phaser: Phaser) {
  /** Increase `currentCount` by 1. */
  def increment(): Unit = { phaser.register(); () }

  /** Decrease `currentCount` by 1. */
  def decrement(): Unit = { phaser.arriveAndDeregister(); () }

  /** Block until `currentCount` reaches 0. */
  def waitUntil0(): Unit = { phaser.register(); phaser.arriveAndAwaitAdvance(); () }

  /** Current number of `decrement` calls needed before `waitUntil0` calls are unblocked. */
  def currentCount(): Int = phaser.getUnarrivedParties()
}

object TwoWayLatch {
  /** Creates a `TwoWayLatch` whose `currentCount` equals `initialCount`. */
  def apply(initialCount: Int): TwoWayLatch = new TwoWayLatch(new Phaser(initialCount))
}
