package scalaz.stream

import scalaz.Functor
import scalaz.concurrent.Task
import scalaz.stream.Process.{Emit, Halt, Step}
import scalaz.syntax.functor._

object sink {

  /** Promote an effectful function to a `Sink`. */
  def lift[F[_], A](f: A => F[Unit]): Sink[F, A] = channel lift f

}

final class SinkSyntax[F[_], I](val self: Sink[F, I]) extends AnyVal {

  /** Converts `Sink` to `Channel`, that will perform the side effect and echo its input. */
  def toChannel(implicit F: Functor[F]): Channel[F, I, I] =
    self.map(f => (i: I) => f(i).as(i))
}

final class SinkTaskSyntax[I](val self: Sink[Task, I]) extends AnyVal {

  /** converts sink to sink that first pipes received `I0` to supplied p1 */
  def pipeIn[I0](p1: Process1[I0, I]): Sink[Task, I0] = Process.suspend {
    import scalaz.Scalaz._
    // Note: Function `f` from sink `self` may be used for more than 1 element emitted by `p1`.
    @volatile var cur = p1.step
    @volatile var lastF: Option[I => Task[Unit]] = None
    self.takeWhile { _ =>
      cur match {
        case Halt(Cause.End) => false
        case Halt(cause)     => throw new Cause.Terminated(cause)
        case _               => true
      }
    } map { (f: I => Task[Unit]) =>
      lastF = f.some
      (i0: I0) => Task.suspend {
        cur match {
          case Halt(_) => sys.error("Impossible")
          case Step(Emit(piped), cont) =>
            cur = process1.feed1(i0) { cont.continue }.step
            piped.toList.traverse_(f)
          case Step(hd, cont) =>
            val (piped, tl) = process1.feed1(i0)(hd +: cont).unemit
            cur = tl.step
            piped.toList.traverse_(f)
        }
      }
    } onHalt {
      case Cause.Kill =>
        lastF map { f =>
          cur match {
            case Halt(_) => sys.error("Impossible (2)")
            case s@Step(_, _) =>
              s.toProcess.disconnect(Cause.Kill).evalMap(f).drain
          }
        } getOrElse Halt(Cause.Kill)
      case Cause.End  => Process.halt
      case c@Cause.Error(_) => Process.halt.causedBy(c)
    }
  }

}
