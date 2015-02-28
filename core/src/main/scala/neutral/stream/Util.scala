package neutral.stream

import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicInteger
import scalaz.concurrent.Task

private[stream] object Util {

  implicit class AppendSyntax[A](val self: Vector[A]) extends AnyVal {

    /**
     * Helper to fix performance issue on Vector append Seq
     * hopefully this can be removed in scala 2.11
     */
    def fast_++[B >: A](other: Seq[B]): Vector[B] = {
      @tailrec
      def append(acc:Vector[B], rem:Seq[B]) : Vector[B] = {
      //  debug(s"AP: self: ${self.size} other: ${other.size}")
        if (rem.nonEmpty) append(acc :+ rem.head, rem.tail)
        else acc
      }

      @tailrec
      def prepend(acc:Vector[B], rem:Seq[B]) : Vector[B] = {
        if (rem.nonEmpty) prepend(rem.last +: acc, rem.init)
        else acc
      }

      if (self.size < other.size) prepend(other.toVector,self)
      else append(self.toVector, other)
    }
  }


  /**
   * Helper to wrap evaluation of `p` that may cause side-effects by throwing exception.
   */
  private[stream] def Try[F[_], A](p: => Process[F, A]): Process[F, A] =
    try p
    catch {case e: Throwable => Process.fail(e)}


  private[stream] implicit class EitherSyntax[A, B](val self: Either[A, B]) extends AnyVal {
    def bimap[C, D](f: A => C, g: B => D): Either[C, D] = self match {
      case Left(a)  => Left(f(a))
      case Right(b) => Right(g(b))
    }

    def leftMap[C](f: A => C): Either[C, B] = bimap(f, identity)

    def map[C](f: B => C): Either[A, C] = bimap(identity, f)

    def toOption: Option[B] = self.fold(_ => None, Some(_))
  }

  private[stream] implicit class ListSyntax[A](val self: List[A]) extends AnyVal {
    def traverseTask_[B](f: A => Task[B]): Task[Unit] =
      self.foldLeft(Task.now(())) { (acc, a) =>
        acc.map(_ => f(a).map(_ => ()))
      }
  }
}
