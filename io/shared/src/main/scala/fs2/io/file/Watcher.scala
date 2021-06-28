/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io

import scala.concurrent.duration._

import cats.syntax.all._

/** Allows watching the file system for changes to directories and files by using the platform's `WatchService`.
  */
abstract class Watcher[F[_]] private[io] () {

  import Watcher._

  /** Registers for events on the specified path.
    *
    * This is more feature-rich than the platform's `Path#register`. The supplied path may be
    * a file or directory and events may raised for all descendants of the path. Use [[register]] for
    * a lower-level API.
    *
    * Returns a cancellation task that unregisters the path for events. Unregistration is optional -
    * the `Watcher` will free all resources when it is finalized. Unregistration is only needed
    * when a `Watcher` will continue to be used after unregistration.
    *
    * @param path file or directory to watch for events
    * @param types event types to register for; if `Nil`, all standard event types are registered
    * @param modifiers modifiers to pass to the underlying `WatchService` when registering
    * @return unregistration task
    */
  def watch(
      path: Path,
      types: Seq[Watcher.EventType] = Nil,
      modifiers: Seq[WatchEventModifier] = Nil
  ): F[F[Unit]]

  /** Registers for events on the specified path.
    *
    * This is a low-level abstraction on the platform's `Path#register`. The supplied path must be
    * a directory and events are raised for only direct descendants of the path. Use [[watch]] for
    * a higher level API.
    *
    * Returns a cancellation task that unregisters the path for events. Unregistration is optional -
    * the `Watcher` will free all resources when it is finalized. Unregistration is only needed
    * when a `Watcher` will continue to be used after unregistration.
    *
    * @param path directory to watch for events
    * @param types event types to register for; if `Nil`, all standard event types are registered
    * @param modifiers modifiers to pass to the underlying `WatchService` when registering
    * @return unregistration task
    */
  def register(
      path: Path,
      types: Seq[Watcher.EventType] = Nil,
      modifiers: Seq[WatchEventModifier] = Nil
  ): F[F[Unit]]

  /** Stream of events for paths that have been registered or watched.
    *
    * @param pollTimeout amount of time for which the underlying platform is polled for events
    */
  def events(pollTimeout: FiniteDuration = 1.second): Stream[F, Watcher.Event]
}

object Watcher extends WatcherPlatform {

  /** Type of event raised by `Watcher`. Supports the standard events types as well as arbitrary non-standard types (via `NonStandard`). */
  sealed abstract class EventType
  object EventType extends EventTypePlatform {
    case object Created extends EventType
    case object Deleted extends EventType
    case object Modified extends EventType
    case object Overflow extends EventType
    final case class NonStandard(kind: WatchEventKind[_]) extends EventType
  }

  /** Event raised by `Watcher`. Supports standard events as well as arbitrary non-standard events (via `NonStandard`). */
  sealed abstract class Event
  object Event extends EventPlatform {
    final case class Created(path: Path, count: Int) extends Event
    final case class Deleted(path: Path, count: Int) extends Event
    final case class Modified(path: Path, count: Int) extends Event
    final case class Overflow(count: Int) extends Event
    final case class NonStandard(event: WatchEvent[_], registeredDirectory: Path) extends Event
  }

}
