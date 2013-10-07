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
case class Writer[+F[_],+W,+A](run: Process[F, W \/ A]) {

  def map[B](f: A => B): Writer[F,W,B] =
    Writer(run.map(_.map(f)))

  def mapW[W2](f: W => W2): Writer[F,W2,A] = 
    Writer(run.map(_.leftMap(f)))

  def flatMap[F2[x]>:F[x],W2>:W,B](f: A => Writer[F2,W2,B]): Writer[F2,W2,B] = 
    Writer(run.flatMap(_.fold(s => emit(left(s)), f andThen (_.run))))

  def flatMapW[F2[x]>:F[x],W2,A2>:A](f: W => Writer[F2,W2,A2]): Writer[F2,W2,A2] = 
    Writer(run.flatMap(_.fold(f andThen (_.run), a => emit(right(a)))))

  def lower: Process[F,A] = 
    run.flatMap(_.fold(_ => halt, emit))

  def pipe[B](f: Process1[A,B]): Writer[F,W,B] = 
    Writer(run.pipe(process1.liftR(f)))

  def pipe[W2>:W,B](f: Writer.Process1W[W2,A,B]): Writer[F,W2,B] = 
    Writer(run.pipe(f.run.liftR[W]).flatMap(
      _.fold(
        s => emit(left(s)), 
        e => e.fold(s => emit(left(s)), 
                    b => emit(right(b)))
    )))

  def ++[F2[x]>:F[x],W2>:W,A2>:A](w2: => Writer[F2,W2,A2]): Writer[F2,W2,A2] =
    Writer(run ++ w2.run)

  def w_++[F2[x]>:F[x],W2>:W,A2>:A](w2: => Process[F2, A2]): Writer[F2,W2,A2] =
    Writer(run ++ Writer.lift[F2,A2](w2).run)

  def fby[F2[x]>:F[x],W2>:W,A2>:A](w2: => Writer[F2,W2,A2]): Writer[F2,W2,A2] =
    Writer(run fby w2.run)

  def w_fby[F2[x]>:F[x],W2>:W,A2>:A](w2: => Process[F2, A2]): Writer[F2,W2,A2] =
    Writer(run fby Writer.lift(w2).run)
}

object Writer {

  type Process1W[+S,-I,+O] = Writer[Env[I,Any]#Is,S,O] 
  type TeeW[+S,-I,-I2,+O] = Writer[Env[I,I2]#T,S,O] 
  type WyeW[+S,-I,-I2,+O] = Writer[Env[I,I2]#Y,S,O]
  type Journal[S,-I,+O] = TeeW[Entry[S], S, I, O]
  import Entry._

  case class Log[F[_],S,I](
    commit: Sink[F,S],
    commited: Process[F,S],
    reset: Sink[F,Unit],
    recover: Sink[F,Unit],
    log: Sink[F,I],
    logged: Process[F,I])

  def journal[F[_],S,I,O](
    j: Journal[S,I,O])(log: Log[F,S,I]): Channel[F,I,O] = ???

  def journal[F[_],S,I,O](
    j: Journal[S,I,O])(input: Process[F,I])(l: Log[F,S,I]): Process[F,O] =
    l.commited.tee(l.logged ++ input.observe(l.log))(j.run).flatMap {
      case \/-(o) => Process.emit(o)
      case -\/(e) => e match {
        case Recover => (Process.emitSeq[F,Unit](List(())) to l.recover).drain
        case Reset => (Process.emitSeq[F,Unit](List(())) to l.reset).drain
        case Commit(s) => (Process.emitSeq[F,S](List(s)) to l.commit).drain
      }
    }

  def halt: Writer[Nothing,Nothing,Nothing] = 
    Writer(Process.halt)

  def emit[O](o: O): Writer[Nothing,Nothing,O] = 
    lift(Process.emit(o))

  def tell[S](s: S): Writer[Nothing,S,Nothing] = 
    Writer(Process.emit(left(s)))

  def lift[F[_],A](p: Process[F,A]): Writer[F,Nothing,A] = 
    Writer(p.map(right))

  def logged[F[_],A](p: Process[F,A]): Writer[F,A,A] =
    Writer(p.flatMap(a => emitAll(Vector(left(a), right(a)))))

  def await1[A]: Process1W[Nothing,A,A] =
    lift(Process.await1[A])

  def awaitL[I]: TeeW[Nothing,I,Any,I] =
    lift(Process.awaitL[I])

  def awaitR[I2]: TeeW[Nothing,Any,I2,I2] =
    lift(Process.awaitR[I2])

  def awaitBoth[I,I2]: WyeW[Nothing,I,I2,These[I,I2]] =
    lift(Process.awaitBoth[I,I2])

  /** Commit to a new state. */
  def commit[S](s: S): Writer[Nothing,Entry[S],Nothing] = 
    tell(Commit(s)) 

  /** Tell the driver to reset back to the last commited state. */
  val reset: Writer[Nothing,Entry[Nothing],Nothing] = 
    tell(Reset)

  /** Tell the driver to attempt to recover the current state. */ 
  val recover: Writer[Nothing,Entry[Nothing],Nothing] = 
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

  implicit class WriterSyntax[F[_],S,A](self: Writer[F,S,A]) {
    def observeW(snk: Sink[F,S]): Writer[F,S,A] = {
      Writer(self.run.zipWith(snk)((a,f) => 
        a.fold(
          (s: S) => eval_ { f(s) } ++ Process.emit(left(s)),
          (a: A) => Process.emit(right(a))
        )
      ).flatMap(identity))
    }

    def drainW(snk: Sink[F,S]): Process[F,A] = 
      observeW(snk).lower
  }
}
