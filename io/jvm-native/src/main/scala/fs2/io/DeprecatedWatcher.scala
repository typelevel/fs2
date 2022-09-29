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

import cats.effect.kernel._
import cats.syntax.all._
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

import CollectionCompat._

/** Allows watching the file system for changes to directories and files by using the platform's `WatchService`.
  */
sealed abstract class Watcher[F[_]] {

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
      modifiers: Seq[WatchEvent.Modifier] = Nil
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
      modifiers: Seq[WatchEvent.Modifier] = Nil
  ): F[F[Unit]]

  /** Stream of events for paths that have been registered or watched.
    *
    * @param pollTimeout amount of time for which the underlying platform is polled for events
    */
  def events(pollTimeout: FiniteDuration = 1.second): Stream[F, Watcher.Event]
}

object Watcher {

  /** Type of event raised by `Watcher`. Supports the standard events types as well as arbitrary non-standard types (via `NonStandard`). */
  sealed abstract class EventType
  object EventType {
    case object Created extends EventType
    case object Deleted extends EventType
    case object Modified extends EventType
    case object Overflow extends EventType
    final case class NonStandard(kind: WatchEvent.Kind[_]) extends EventType

    def toWatchEventKind(et: EventType): WatchEvent.Kind[_] =
      et match {
        case EventType.Created           => StandardWatchEventKinds.ENTRY_CREATE
        case EventType.Modified          => StandardWatchEventKinds.ENTRY_MODIFY
        case EventType.Deleted           => StandardWatchEventKinds.ENTRY_DELETE
        case EventType.Overflow          => StandardWatchEventKinds.OVERFLOW
        case EventType.NonStandard(kind) => kind
      }
  }

  /** Event raised by `Watcher`. Supports standard events as well as arbitrary non-standard events (via `NonStandard`). */
  sealed abstract class Event
  object Event {
    final case class Created(path: Path, count: Int) extends Event
    final case class Deleted(path: Path, count: Int) extends Event
    final case class Modified(path: Path, count: Int) extends Event
    final case class Overflow(count: Int) extends Event
    final case class NonStandard(event: WatchEvent[_], registeredDirectory: Path) extends Event

    /** Converts a NIO `WatchEvent` to an FS2 `Watcher.Event`.
      *
      * @param e event to convert
      * @param registeredDirectory path of the directory for which the event's path is relative
      */
    def fromWatchEvent(e: WatchEvent[_], registeredDirectory: Path): Event =
      e match {
        case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_CREATE =>
          Event.Created(registeredDirectory.resolve(e.context), e.count)
        case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_MODIFY =>
          Event.Modified(registeredDirectory.resolve(e.context), e.count)
        case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_DELETE =>
          Event.Deleted(registeredDirectory.resolve(e.context), e.count)
        case e if e.kind == StandardWatchEventKinds.OVERFLOW =>
          Event.Overflow(e.count)
        case e => Event.NonStandard(e, registeredDirectory)
      }

    /** Determines the path for which the supplied event references. */
    def pathOf(event: Event): Option[Path] =
      event match {
        case Event.Created(p, _)  => Some(p)
        case Event.Deleted(p, _)  => Some(p)
        case Event.Modified(p, _) => Some(p)
        case Event.Overflow(_)    => None
        case Event.NonStandard(e, registeredDirectory) =>
          if (e.context.isInstanceOf[Path])
            Some(registeredDirectory.resolve(e.context.asInstanceOf[Path]))
          else None
      }
  }

  /** Creates a watcher for the default file system. */
  def default[F[_]](implicit F: Async[F]): Resource[F, Watcher[F]] =
    Resource
      .eval(F.blocking(FileSystems.getDefault))
      .flatMap(fromFileSystem(_))

  /** Creates a watcher for the supplied file system. */
  def fromFileSystem[F[_]](
      fs: FileSystem
  )(implicit F: Async[F]): Resource[F, Watcher[F]] =
    Resource(F.blocking(fs.newWatchService).flatMap { ws =>
      fromWatchService(ws).map(w => w -> F.blocking(ws.close))
    })

  private case class Registration[F[_]](
      path: Path,
      singleFile: Boolean,
      key: WatchKey,
      types: Seq[EventType],
      modifiers: Seq[WatchEvent.Modifier],
      recurse: Boolean,
      suppressCreated: Boolean,
      cleanup: F[Unit]
  )

  /** Creates a watcher for the supplied NIO `WatchService`. */
  def fromWatchService[F[_]](
      ws: WatchService
  )(implicit F: Async[F]): F[Watcher[F]] =
    Ref
      .of[F, List[Registration[F]]](Nil)
      .map(new DefaultWatcher(ws, _))

  private class DefaultWatcher[F[_]](
      ws: WatchService,
      registrations: Ref[F, List[Registration[F]]]
  )(implicit
      F: Async[F]
  ) extends Watcher[F] {
    private def isDir(p: Path): F[Boolean] =
      F.blocking(Files.isDirectory(p))

    private def track(r: Registration[F]): F[F[Unit]] =
      registrations
        .update(rs => r :: rs)
        .as {
          registrations.modify { s =>
            val filtered = s.filterNot(_ == r)
            val cleanup = if (s.contains(r)) r.cleanup else F.unit
            val cancelKey =
              if (filtered.forall(_.key != r.key)) F.blocking(r.key.cancel) else F.unit
            filtered -> (cancelKey *> cleanup)
          }.flatten
        }

    override def watch(
        path: Path,
        types: Seq[Watcher.EventType] = Nil,
        modifiers: Seq[WatchEvent.Modifier] = Nil
    ): F[F[Unit]] =
      isDir(path).flatMap { dir =>
        if (dir) watchDirectory(path, types, modifiers)
        else watchFile(path, types, modifiers)
      }

    private def watchDirectory(
        path: Path,
        types: Seq[EventType],
        modifiers: Seq[WatchEvent.Modifier]
    ): F[F[Unit]] = {
      val (supplementedTypes, suppressCreated) =
        if (types.isEmpty)
          (
            List(EventType.Created, EventType.Deleted, EventType.Modified, EventType.Overflow),
            false
          )
        else if (types.contains(EventType.Created)) (types, false)
        else (EventType.Created +: types, true)
      val dirs: F[List[Path]] = F.blocking {
        var dirs: List[Path] = Nil
        Files.walkFileTree(
          path,
          new SimpleFileVisitor[Path] {
            override def preVisitDirectory(path: Path, attrs: BasicFileAttributes) = {
              dirs = path :: dirs
              FileVisitResult.CONTINUE
            }
          }
        )
        dirs
      }
      dirs.flatMap(
        _.traverse(path =>
          registerUntracked(path, supplementedTypes, modifiers).flatMap(key =>
            track(
              Registration(
                path,
                false,
                key,
                supplementedTypes,
                modifiers,
                true,
                suppressCreated,
                F.unit
              )
            )
          )
        ).map(_.sequence.void)
      )
    }

    private def watchFile(
        path: Path,
        types: Seq[Watcher.EventType],
        modifiers: Seq[WatchEvent.Modifier]
    ): F[F[Unit]] =
      registerUntracked(path.getParent, types, modifiers).flatMap(key =>
        track(
          Registration(
            path,
            true,
            key,
            types,
            modifiers,
            false,
            false,
            F.unit
          )
        )
      )

    override def register(
        path: Path,
        types: Seq[Watcher.EventType],
        modifiers: Seq[WatchEvent.Modifier]
    ): F[F[Unit]] =
      registerUntracked(path, types, modifiers).flatMap(key =>
        track(Registration(path, false, key, types, modifiers, false, false, F.unit))
      )

    private def registerUntracked(
        path: Path,
        types: Seq[Watcher.EventType],
        modifiers: Seq[WatchEvent.Modifier]
    ): F[WatchKey] =
      F.blocking {
        val typesWithDefaults =
          if (types.isEmpty)
            List(EventType.Created, EventType.Deleted, EventType.Modified, EventType.Overflow)
          else types
        val kinds = typesWithDefaults.map(EventType.toWatchEventKind)
        path.register(ws, kinds.toArray, modifiers: _*)
      }

    override def events(pollTimeout: FiniteDuration): Stream[F, Event] =
      unfilteredEvents(pollTimeout).evalMap(e => registrations.get.map((e, _))).flatMap {
        case ((key, events), registrations) =>
          val regs = registrations.filter(_.key == key)
          val filteredEvents = events.filter { e =>
            regs.exists { reg =>
              val singleFileMatch = reg.singleFile && Event.pathOf(e) == Some(reg.path)
              val dirMatch =
                !reg.singleFile && !(e.isInstanceOf[Event.Created] && reg.suppressCreated)
              singleFileMatch || dirMatch
            }
          }
          val recurse: Stream[F, Event] =
            if (regs.exists(_.recurse)) {
              val created = events.collect { case Event.Created(p, _) => p }
              def watchIfDirectory(p: Path): F[(F[Unit], List[Event])] =
                F.blocking(Files.isDirectory(p))
                  .ifM(
                    watch(
                      p,
                      Seq(EventType.Created),
                      regs.headOption.map(_.modifiers).getOrElse(Nil)
                    ).flatMap { cancel =>
                      val events: F[List[Event]] = F.blocking {
                        var evs: List[Event.Created] = Nil
                        Files.list(p).forEach(d => evs = Event.Created(d, 1) :: evs)
                        evs
                      }
                      events.map(cancel -> _)
                    },
                    F.pure(F.unit -> List.empty[Event])
                  )
              val subregisters: F[List[Event]] =
                created.traverse(watchIfDirectory).flatMap { x =>
                  val (cancels, events) = x.separate
                  val updateRegistration: F[Unit] = this.registrations.update { m =>
                    val idx = m.indexWhere(_.key == key)
                    if (idx >= 0) {
                      val existing = m(idx)
                      val cancelAll: F[Unit] = cancels.sequence.void
                      m.updated(idx, existing.copy(cleanup = existing.cleanup >> cancelAll))
                    } else m
                  }.void
                  updateRegistration.as(events.flatten)
                }
              Stream.eval(subregisters).flatMap(Stream.emits(_))
            } else Stream.empty
          recurse ++ (if (filteredEvents.isEmpty) Stream.empty
                      else Stream.emits(filteredEvents))
      }

    private def unfilteredEvents(
        pollTimeout: FiniteDuration
    ): Stream[F, (WatchKey, List[Event])] = {
      val poll: F[Option[(WatchKey, List[Event])]] = F.blocking {
        val key = ws.poll(pollTimeout.toMillis, TimeUnit.MILLISECONDS)
        if (key eq null) None
        else {
          val events = key.pollEvents.asScala.toList
          key.reset
          val keyPath = key.watchable.asInstanceOf[Path]
          Some(key -> events.map(evt => Event.fromWatchEvent(evt, keyPath)))
        }
      }
      Stream
        .repeatEval(poll)
        .flatMap(_.map(Stream.emit(_)).getOrElse(Stream.empty))
    }
  }
}
