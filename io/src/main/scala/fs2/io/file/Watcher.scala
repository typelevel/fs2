package fs2
package io

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect._
import cats.implicits._
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Allows watching the file system for changes to directories and files by using the platform's `WatchService`.
 */
sealed abstract class Watcher[F[_]] {

  /**
   * Registers for events on the specified path.
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
  def watch(path: Path, types: Seq[Watcher.EventType] = Nil, modifiers: Seq[WatchEvent.Modifier] = Nil): F[F[Unit]]

  /**
   * Registers for events on the specified path.
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
  def register(path: Path, types: Seq[Watcher.EventType] = Nil, modifiers: Seq[WatchEvent.Modifier] = Nil): F[F[Unit]]

  /**
   * Stream of events for paths that have been registered or watched.
   *
   * @param pollTimeout amount of time for which the underlying platform is polled for events
   */
  def events(pollTimeout: FiniteDuration = 1.second): Stream[F,Watcher.Event]
}

object Watcher {

  /** Type of event raised by `Watcher`. Supports the standard events types as well as arbitrary non-standard types (via `NonStandard`). */
  sealed abstract class EventType
  object EventType {
    final case object Created extends EventType
    final case object Deleted extends EventType
    final case object Modified extends EventType
    final case object Overflow extends EventType
    final case class NonStandard(kind: WatchEvent.Kind[_]) extends EventType

    def toWatchEventKind(et: EventType): WatchEvent.Kind[_] = et match {
      case EventType.Created => StandardWatchEventKinds.ENTRY_CREATE
      case EventType.Modified => StandardWatchEventKinds.ENTRY_MODIFY
      case EventType.Deleted => StandardWatchEventKinds.ENTRY_DELETE
      case EventType.Overflow => StandardWatchEventKinds.OVERFLOW
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

    /**
     * Converts a NIO `WatchEvent` to an FS2 `Watcher.Event`.
     *
     * @param e event to convert
     * @param registeredDirectory path of the directory for which the event's path is relative
     */
    def fromWatchEvent(e: WatchEvent[_], registeredDirectory: Path): Event = e match {
      case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_CREATE => Event.Created(registeredDirectory resolve e.context, e.count)
      case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_MODIFY => Event.Modified(registeredDirectory resolve e.context, e.count)
      case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_DELETE => Event.Deleted(registeredDirectory resolve e.context, e.count)
      case e if e.kind == StandardWatchEventKinds.OVERFLOW => Event.Overflow(e.count)
      case e => Event.NonStandard(e, registeredDirectory)
    }

    /** Determines the path for which the supplied event references. */
    def pathOf(event: Event): Option[Path] = event match {
      case Event.Created(p, _) => Some(p)
      case Event.Deleted(p, _) => Some(p)
      case Event.Modified(p, _) => Some(p)
      case Event.Overflow(_) => None
      case Event.NonStandard(e, registeredDirectory) =>
        if (e.context.isInstanceOf[Path]) Some(registeredDirectory resolve e.context.asInstanceOf[Path])
        else None
    }
  }

  /** Creates a watcher for the default file system. */
  def default[F[_]](implicit F: Effect[F], ec: ExecutionContext): Stream[F,Watcher[F]] =
    Stream.eval(F.delay(FileSystems.getDefault)).flatMap(fromFileSystem(_))

  /** Creates a watcher for the supplied file system. */
  def fromFileSystem[F[_]](fs: FileSystem)(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Watcher[F]] =
    Stream.bracket(F.delay(fs.newWatchService))(ws => Stream.eval(fromWatchService(ws)), ws => F.delay(ws.close))

  private case class Registration[F[_]](
    types: Seq[EventType],
    modifiers: Seq[WatchEvent.Modifier],
    eventPredicate: Event => Boolean,
    recurse: Boolean,
    suppressCreated: Boolean,
    cleanup: F[Unit])

  /** Creates a watcher for the supplied NIO `WatchService`. */
  def fromWatchService[F[_]](ws: WatchService)(implicit F: Effect[F], ec: ExecutionContext): F[Watcher[F]] =
    async.signalOf[F,Map[WatchKey,Registration[F]]](Map.empty).map(new DefaultWatcher(ws, _))

  private class DefaultWatcher[F[_]](ws: WatchService, registrations: async.mutable.Signal[F,Map[WatchKey,Registration[F]]])(implicit F: Sync[F]) extends Watcher[F] {

    private def isDir(p: Path): F[Boolean] = F.delay(Files.isDirectory(p))

    private def track(key: WatchKey, r: Registration[F]): F[F[Unit]] =
      registrations.modify(_.updated(key, r)).as(
        F.delay(key.cancel) *> registrations.modify(_ - key).flatMap(c => c.previous.get(key).map(_.cleanup).getOrElse(F.pure(()))))

    override def watch(path: Path, types: Seq[Watcher.EventType] = Nil, modifiers: Seq[WatchEvent.Modifier] = Nil): F[F[Unit]] = {
      isDir(path).flatMap { dir =>
        if (dir) watchDirectory(path, types, modifiers)
        else watchFile(path, types, modifiers)
      }
    }

    private def watchDirectory(path: Path, types: Seq[EventType], modifiers: Seq[WatchEvent.Modifier]): F[F[Unit]] = {
      val (supplementedTypes, suppressCreated) =
        if (types.isEmpty) (List(EventType.Created, EventType.Deleted, EventType.Modified, EventType.Overflow), false)
        else if (types.contains(EventType.Created)) (types, false)
        else (EventType.Created +: types, true)
      val dirs: F[List[Path]] = F.delay {
        var dirs: List[Path] = Nil
        Files.walkFileTree(path, new SimpleFileVisitor[Path] {
          override def preVisitDirectory(path: Path, attrs: BasicFileAttributes) = {
            dirs = path :: dirs
            FileVisitResult.CONTINUE
          }
        })
        dirs
      }
      dirs.flatMap(_.traverse(registerUntracked(_, supplementedTypes, modifiers).
        flatMap(key => track(key, Registration(supplementedTypes, modifiers, _ => true, true, suppressCreated, F.unit)))
      ).map(_.sequence.void))
    }

    private def watchFile(path: Path, types: Seq[Watcher.EventType], modifiers: Seq[WatchEvent.Modifier]): F[F[Unit]] = {
      registerUntracked(path.getParent, types, modifiers).flatMap(key =>
        track(key, Registration(types, modifiers, e => Event.pathOf(e).map(ep => path == ep).getOrElse(true), false, false, F.unit)))
    }

    override def register(path: Path, types: Seq[Watcher.EventType], modifiers: Seq[WatchEvent.Modifier]): F[F[Unit]] =
      registerUntracked(path, types, modifiers).flatMap(key => track(key, Registration(types, modifiers, _ => true, false, false, F.unit)))

    private def registerUntracked(path: Path, types: Seq[Watcher.EventType], modifiers: Seq[WatchEvent.Modifier]): F[WatchKey] = F.delay {
      val typesWithDefaults = if (types.isEmpty) List(EventType.Created, EventType.Deleted, EventType.Modified, EventType.Overflow) else types
      val kinds = typesWithDefaults.map(EventType.toWatchEventKind)
      path.register(ws, kinds.toArray, modifiers: _*)
    }

    override def events(pollTimeout: FiniteDuration): Stream[F,Event] =
      unfilteredEvents(pollTimeout).zip(registrations.continuous).flatMap { case ((key, events), registrations) =>
        val reg = registrations.get(key)
        val filteredEvents = reg.map(reg => events.filter(e =>
          reg.eventPredicate(e) && !(e.isInstanceOf[Event.Created] && reg.suppressCreated)
        )).getOrElse(Nil)
        val recurse: Stream[F,Event] =
          if (reg.map(_.recurse).getOrElse(false)) {
            val created = events.collect { case Event.Created(p, _) => p }
            def watchIfDirectory(p: Path): F[(F[Unit], List[Event])] = {
              F.delay(Files.isDirectory(p)).ifM(
                watch(p, Seq(EventType.Created), reg.map(_.modifiers).getOrElse(Nil)).flatMap { cancel =>
                  val events: F[List[Event]] = F.delay {
                    var dirs: List[Path] = Nil
                    Files.list(p).forEach(d => dirs = d :: dirs)
                    dirs.map(Event.Created(_, 1))
                  }
                  events.map(cancel -> _)
                },
                F.pure(F.pure(()) -> List.empty[Event])
              )
            }
            val subregisters: F[List[Event]] = created.traverse(watchIfDirectory).
              flatMap { x =>
                val (cancels, events) = x.separate
                val cancelAll: F[Unit] = cancels.sequence.void
                val updateRegistration: F[Unit] = this.registrations.modify(m =>
                  m.get(key).map(r => m.updated(key, r.copy(cleanup = r.cleanup *> cancelAll))).getOrElse(m)
                ).void
                updateRegistration.as(events.flatten)
              }
            Stream.eval(subregisters).flatMap(Stream.emits(_))
          } else Stream.empty
        recurse ++ (if (filteredEvents.isEmpty) Stream.empty else Stream.emits(filteredEvents))
      }

    private def unfilteredEvents(pollTimeout: FiniteDuration): Stream[F,(WatchKey,List[Event])] = {
      val poll: F[Option[(WatchKey, List[Event])]] = F.delay {
        val key = ws.poll(pollTimeout.toMillis, TimeUnit.MILLISECONDS)
        if (key eq null) None
        else {
          val events = key.pollEvents.asScala.toList
          key.reset
          val keyPath = key.watchable.asInstanceOf[Path]
          Some(key -> events.map(evt => Event.fromWatchEvent(evt, keyPath)))
        }
      }
      Stream.repeatEval(poll).flatMap(_.map(Stream.emit(_)).getOrElse(Stream.empty))
    }
  }
}
