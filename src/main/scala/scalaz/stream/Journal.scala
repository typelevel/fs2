package scalaz.stream

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

object Writer extends Journals {

  type Process1W[+S,-I,+O] = Writer[Env[I,Any]#Is,S,O] 
  type TeeW[+S,-I,-I2,+O] = Writer[Env[I,I2]#T,S,O] 
  type WyeW[+S,-I,-I2,+O] = Writer[Env[I,I2]#Y,S,O]

  def emit[O](o: O): Writer[Nothing,Nothing,O] = 
    lift(Process.emit(o))

  def tell[S](s: S): Writer[Nothing,S,Nothing] = 
    Writer(Process.emit(right(s)))

  def lift[F[_],A](p: Process[F,A]): Writer[F,Nothing,A] = 
    Writer(p.map(left))

  def await1[A]: Process1W[Nothing,A,A] =
    lift(Process.await1[A])

  def awaitL[I]: TeeW[Nothing,I,Any,I] =
    lift(Process.awaitL[I])

  def awaitR[I2]: TeeW[Nothing,Any,I2,I2] =
    lift(Process.awaitR[I2])

  def awaitBoth[I,I2]: WyeW[Nothing,I,I2,These[I,I2]] =
    lift(Process.awaitBoth[I,I2])
}

/** 
 * A `Process` which transactionally updates a given
 * state of type `S`, recording inputs `I` in the process 
 * of performing the transaction. The most recent `commit`,
 * together with any pending messages, can be used to 
 * resume the `Journal` from an identical state, which 
 * makes this type useful as a building block for persistent,
 * distributed, or replicated streams. 
 */
case class Journal[-P[_],+F[_],S,I,+O] private[stream](
    resume: (S, Process[P,I]) => Writer[F,Entry[S,I],O]) {

  def map[O2](f: O => O2): Journal[P,F,S,I,O2] =
    rewrite(_.map(f)) 

  def rewrite[F2[_],O2](
      f: Writer[F,Entry[S,I],O] => Writer[F2,Entry[S,I],O2]): Journal[P,F2,S,I,O2] =
    Journal((s: S, pending: Process[P,I]) => f(resume(s, pending)))

  def pipe[O2](f: Process1[O,O2]): Journal[P,F,S,I,O2] = 
    rewrite(_.pipe(f))

  def |>[O2](f: Process1[O,O2]): Journal[P,F,S,I,O2] = 
    rewrite(_.pipe(f))

  def extend[F2[x]>:F[x],O2>:O](f: S => Writer[F2,Entry[S,I],O2]): 
      Journal[P,F2,S,I,O2] = 
    Journal((s: S, pending: Process[P,I]) => Writer(resume(s,pending).run.flatMap {
      case \/-(entry) => entry match {
        case r@Reset => emit(right(r))
        case l@Log(_) => emit(right(l))
        case c@Commit(s) => f(s).run
      } 
      case o@(-\/(_)) => emit(o)
    }))

  def ++[P2[y]<:P[y],F2[x]>:F[x],O2>:O](j2: Journal[P2,F2,S,I,O2]): 
      Journal[P2,F2,S,I,O2] =
    extend(s => j2.resume(s, halt))

  def flatMap[P2[y]<:P[y],F2[x]>:F[x],O2](f: O => Writer[F2,Entry[S,I],O2]):
      Journal[P2,F2,S,I,O2] = 
    Journal((s: S, pending: Process[P2,I]) => 
      Writer { resume(s, pending).flatMap(f).run.pipe(
        process1.takeThrough {
          case \/-(Commit(_)) => false
          case _ => true
        }
      )}
    )
}

trait Journals {

  import Writer._

  /** Write a single (uncommited) value to the journal. */
  def log[I](i: I): Writer[Nothing,Entry[Nothing,I],Nothing] = 
    Writer(Process.emit(right(Log(i)))) 

  /** Commit to a new state, clearing any uncommited values. */
  def commit[S](s: S): Writer[Nothing,Entry[S,Nothing],Nothing] = 
    Writer(Process.emit(right(Commit(s)))) 

  /** Reset back to the last commited state. */
  val reset: Writer[Nothing,Entry[Nothing,Nothing],Nothing] = 
    Writer(Process.emit(right(Reset)))

  def read[S]: Journal[Any,Nothing,S,Nothing,S] = 
    Journal[Any,Nothing,S,Nothing,S](
      (s:S, pending: Process[Any,Nothing]) => emit(s) ++ commit(s))
}

object Journal extends Journals {
  
  def journal[F[_],S,I](initialize: S => Process1[I,S]): 
    Journal[F,F,S,I,Nothing] = Journal[F,F,S,I,Nothing](
      (s:S, pending: Process[F,I]) => 
        Writer(pending.pipe(initialize(s)).flatMap(s0 => 
          Process.emit(right(Commit(s0)))))
    )

  sealed trait Entry[+S,+A] { 
    def map[B](f: A => B): Entry[S,B] = this match {
      case Log(a) => Log(f(a)) 
      case r@Reset => r
      case c@Commit(_) => c 
    }
    def mapS[S2](f: S => S2): Entry[S2,A] = this match {
      case l@Log(_) => l 
      case r@Reset => r
      case Commit(s) => Commit(f(s))
    }
  }
  
  case class Log[A](a: A) extends Entry[Nothing,A]
  case class Commit[S](s: S) extends Entry[S,Nothing]
  case object Reset extends Entry[Nothing,Nothing]

  implicit def lift[S,A,B](p: Process1[A,B]): Process1[Entry[S,A],Entry[S,B]] =
    p match {
      case h@Halt(_) => h
      case Emit(h, t) => Emit(h map (Log(_)), lift(t))
      case _ => await1[Entry[S,A]].flatMap {
        case Log(a) => lift(process1.feed1(a)(p))
        case c@Commit(_) => emit(c) ++ lift(p) 
        case r@Reset => emit(r) ++ lift(p)
      }
    }
}
