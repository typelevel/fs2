package scalaz.stream

import collection.immutable.Vector
import Process._
import scalaz.\/._
import scalaz._

/** 
 * A `Writer[F,W,A]` wraps a `Process[F, W \/ A]` with some 
 * convenience functions for working with either the written
 * values (the `W`) or the output values (the `A`).
 * 
 * This is useful for logging or other situations where we
 * want to emit some values 'on the side' while doing something
 * else with the main output of a `Process`.
 */
object Writer {

  type Writer[+F[_],+W,+A] = Process[F, W \/ A]
  type Process1W[+W,-I,+O] = Process1[I,W \/ O]
  type TeeW[+W,-I,-I2,+O] = Tee[I,I2,W \/ O]
  type WyeW[+W,-I,-I2,+O] = Wye[I,I2,W \/ O]
  type Transaction[S,-I,+O] = TeeW[Entry[S], I, S, O]

  import Entry._

  def transaction[F[_],S,I,O](
    j: Transaction[S,I,O])(input: Process[F,I])(l: Journal[F,S,I]): Process[F,O] =
    (l.logged ++ input.observe(l.log)).tee(l.commited)(j).flatMap {
      case \/-(o) => Process.emit(o)
      case -\/(e) => e match {
        case Recover => (Process.emitSeq[F,Unit](List(())) to l.recover).drain
        case Reset => (Process.emitSeq[F,Unit](List(())) to l.reset).drain
        case Commit(s) => (Process.emitSeq[F,S](List(s)) to l.commit).drain
      }
    }

  def emitO[O](o: O): Process[Nothing, Nothing \/ O] = 
    liftW(Process.emit(o))

  def emitW[S](s: S): Process[Nothing, S \/ Nothing] = 
    Process.emit(left(s))

  def tell[S](s: S): Process[Nothing, S \/ Nothing] = 
    emitW(s) 

  def liftW[F[_],A](p: Process[F,A]): Writer[F,Nothing,A] = 
    p.map(right)

  def logged[F[_],A](p: Process[F,A]): Writer[F,A,A] =
    p.flatMap(a => emitAll(Vector(left(a), right(a))))

  def await1W[A]: Process1W[Nothing,A,A] =
    liftW(Process.await1[A])

  def awaitLW[I]: TeeW[Nothing,I,Any,I] =
    liftW(Process.awaitL[I])

  def awaitRW[I2]: TeeW[Nothing,Any,I2,I2] =
    liftW(Process.awaitR[I2])

  def awaitBothW[I,I2]: WyeW[Nothing,I,I2,These[I,I2]] =
    liftW(Process.awaitBoth[I,I2])

  /** Commit to a new state. */
  def commit[S](s: S): Process[Nothing,Entry[S] \/ Nothing] = 
    tell(Commit(s)) 

  /** Tell the driver to reset back to the last commited state. */
  val reset: Process[Nothing,Entry[Nothing] \/ Nothing] = 
    tell(Reset)

  /** Tell the driver to attempt to recover the current state. */ 
  val recover: Process[Nothing,Entry[Nothing] \/ Nothing] = 
    tell(Reset)

  sealed trait Entry[+S] { 
    def map[S2](f: S => S2): Entry[S2] = this match {
      case r@Reset => r
      case r@Recover => r
      case Commit(s) => Commit(f(s))
    }
  }

  object Entry {
    case class Commit[S](s: S) extends Entry[S]
    case object Reset extends Entry[Nothing]
    case object Recover extends Entry[Nothing]
  }

  import scalaz.concurrent.Task

  implicit class WriterSyntax[F[_],W,O](self: Writer[F,W,O]) {

    def observeW(snk: Sink[F,W]): Writer[F,W,O] =
      self.zipWith(snk)((a,f) => 
        a.fold(
          (s: W) => eval_ { f(s) } ++ Process.emit(left(s)),
          (a: O) => Process.emit(right(a))
        )
      ).flatMap(identity)

    def drainW(snk: Sink[F,W]): Process[F,O] = 
      observeW(snk).stripW

    def mapW[W2](f: W => W2): Writer[F,W2,O] = 
      self.map(_.leftMap(f))

    def mapO[B](f: O => B): Writer[F,W,B] =
      self.map(_.map(f))

    def flatMapO[F2[x]>:F[x],W2>:W,B](f: O => Writer[F2,W2,B]): Writer[F2,W2,B] = 
      self.flatMap(_.fold(s => emit(left(s)), f))

    def flatMapW[F2[x]>:F[x],W2,O2>:O](f: W => Writer[F2,W2,O2]): Writer[F2,W2,O2] = 
      self.flatMap(_.fold(f, a => emit(right(a))))

    def stripW: Process[F,O] = 
      self.flatMap(_.fold(_ => halt, emit))

    def stripO: Process[F,O] = 
      self.flatMap(_.fold(_ => halt, emit))

    def pipeO[B](f: Process1[O,B]): Writer[F,W,B] = 
      self.pipe(process1.liftR(f))
  }
}
