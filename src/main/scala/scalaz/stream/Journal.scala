package scalaz.stream

import collection.immutable.Vector
import Process._
import Journal._
import scalaz.\/._
import scalaz._

case class Writer[+F[_],+S,+A](run: Process[F, A \/ S]) {

  def map[B](f: A => B): Writer[F,S,B] = 
    Writer(run.map(_.leftMap(f)))

  def mapW[S2](f: S => S2): Writer[F,S2,A] = 
    Writer(run.map(_.map(f)))

  def flatMap[F2[x]>:F[x],S2>:S,B](f: A => Writer[F2,S2,B]): Writer[F2,S2,B] = 
    Writer(run.flatMap(_.fold(f andThen (_.run), s => emit(right(s)))))

  def flatMapW[F2[x]>:F[x],S2,A2>:A](f: S => Writer[F2,S2,A2]): Writer[F2,S2,A2] = 
    Writer(run.flatMap(_.fold(a => emit(left(a)), f andThen (_.run))))

  def pipe[B](f: Process1[A,B]): Writer[F,S,B] = 
    Writer(run.pipe(process1.liftL(f)))

  def ++[F2[x]>:F[x],S2>:S,A2>:A](w2: => Writer[F2,S2,A2]): Writer[F2,S2,A2] =
    Writer(run ++ w2.run)

  def w_++[F2[x]>:F[x],S2>:S,A2>:A](w2: => Process[F2, A2]): Writer[F2,S2,A2] =
    Writer(run ++ Writer.lift[F2,A2](w2).run)

  def fby[F2[x]>:F[x],S2>:S,A2>:A](w2: => Writer[F2,S2,A2]): Writer[F2,S2,A2] =
    Writer(run then w2.run)

  def w_fby[F2[x]>:F[x],S2>:S,A2>:A](w2: => Process[F2, A2]): Writer[F2,S2,A2] =
    Writer(run then Writer.lift(w2).run)
}

object Writer {
  
  import Journal._

  type Process1W[+S,-I,+O] = Writer[Env[I,Any]#Is,S,O] 
  type TeeW[+S,-I,-I2,+O] = Writer[Env[I,I2]#T,S,O] 
  type WyeW[+S,-I,-I2,+O] = Writer[Env[I,I2]#Y,S,O]
  type Journal[S,-I,+O] = WyeW[Entry[S], S, I, O]
  // type Journal[S,-I,+O] = WyeW[Entry[S], R \/ S, I, O]
  // case object Recover extends Entry

  case class Log[F[_],S,I](
    commit: Sink[F,S],
    commited: Process[F,S],
    reset: Sink[F,Unit],
    log: Sink[F,I],
    logged: Process[F,I])

  def journal[F[_],S,I,O](
    j: Journal[S,I,O])(log: Log[F,S,I]): Channel[F,I,O] = ???

  def journal[F[_]:Nondeterminism:Catchable,S,I,O](
    j: Journal[S,I,O])(input: Process[F,I])(l: Log[F,S,I]): Process[F,O] =
    l.commited.wye(l.logged ++ input.observe(l.log))(j.run).flatMap {
      case -\/(o) => Process.emit(o)
      case \/-(e) => e match {
        case Reset => (Process.emitSeq[F,Unit](List(())) to l.reset).drain
        case Commit(s) => (Process.emitSeq[F,S](List(s)) to l.commit).drain
      }
    }

  def halt: Writer[Nothing,Nothing,Nothing] = 
    Writer(Process.halt)

  def emit[O](o: O): Writer[Nothing,Nothing,O] = 
    lift(Process.emit(o))

  def tell[S](s: S): Writer[Nothing,S,Nothing] = 
    Writer(Process.emit(right(s)))

  def lift[F[_],A](p: Process[F,A]): Writer[F,Nothing,A] = 
    Writer(p.map(left))

  def logged[F[_],A](p: Process[F,A]): Writer[F,A,A] =
    Writer(p.flatMap(a => emitAll(Vector(right(a), left(a)))))

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

  /** Reset back to the last commited state. */
  val reset: Writer[Nothing,Entry[Nothing],Nothing] = 
    tell(Reset)
}


object Journal {
  
  val W = Writer
  val P = Process

  sealed trait Entry[+S] { 
    def map[S2](f: S => S2): Entry[S2] = this match {
      case r@Reset => r
      case Commit(s) => Commit(f(s))
    }
  }
  
  case class Commit[S](s: S) extends Entry[S]
  case object Reset extends Entry[Nothing]

}
