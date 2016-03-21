package fs2

import fs2.util.Task

/**
 * Sanity test - not run as part of unit tests, but this should run forever
 * at constant memory.
 */
object ResourceTrackerSanityTest extends App {

   val big = Stream.constant(1).flatMap { n =>
      Stream.bracket(Task.delay(()))(_ => Stream.emits(List(1, 2, 3)), _ => Task.delay(()))
   }
   big.run.run.run
}
