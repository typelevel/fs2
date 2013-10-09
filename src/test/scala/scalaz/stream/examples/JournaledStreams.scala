package scalaz.stream

import scalaz.concurrent.Task
import scalaz._
import scalaz.\/._
import Process._

import org.scalacheck._
import Prop._

object JournaledStreams extends Properties("journaled-streams") {

  // DISCLAIMER: this is a work in progress

  val W = Writer
  type Transaction[S,-I,+O] = TeeW[Entry[S], I, S, O]
  import Entry._

  /* 
  Message used to indicate arrival and departure of `A` values.
  We can 'sum' these messages to get the current set of available
  `A` values.
  */
  trait Availability[+A]
  case class Arrive[+A](a: A) extends Availability[A]
  case class Depart[+A](a: A) extends Availability[A]

  /* A `Process1` to integrate an availability signal. */
  def availability[A](cur: Set[A] = Set()): Process1[Availability[A], Set[A]] = 
    await1[Availability[A]].flatMap {
      case Arrive(a) => val c2 = cur + a; emit(c2)
      case Depart(a) => val c2 = cur - a; emit(c2) 
    }.repeat

  case class Journal[F[_],S,I](
    commit: Sink[F,S],
    commited: Process[F,S],
    reset: Sink[F,Unit],
    recover: Sink[F,Unit],
    log: Sink[F,I],
    logged: Process[F,I])
  
  def dummyJournal[F[_],S,I]: Journal[F,S,I] = 
    Journal(halt, halt, halt, halt, halt, halt)

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

  property("transaction") = secure {
    
    /* 
    Update the availability, transactionally, and emit the 
    current number available. 
    */
    val t: Transaction[Set[Int], Availability[Int], Int] = 
      for {
        s <- awaitR[Set[Int]]      // read current state
        s2 <- availability[Int](s) // compute current availability set
        n <- commit(s2) ++ emitO(s2.size)
      } yield n
    
    val avail: Process[Task,Availability[Int]] = halt 
    val j: Journal[Task,Set[Int],Availability[Int]] = dummyJournal 

    /* This `Process` will be persisted. */
    val numAvail: Process[Task,Int] = transaction(t)(avail)(j)

    true
  }

  /** Commit to a new state. */
  def commit[S](s: S): Process[Nothing,Entry[S] \/ Nothing] = 
    tell(Commit(s)) 

  /** Tell the driver to reset back to the last commited state. */
  val reset: Process[Nothing,Entry[Nothing] \/ Nothing] = 
    tell(Reset)

  /** Tell the driver to attempt to recover the current state. */ 
  val recover: Process[Nothing,Entry[Nothing] \/ Nothing] = 
    tell(Recover)

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
}
