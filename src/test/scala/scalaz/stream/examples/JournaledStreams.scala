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
  import W.Transaction

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

  import W.Entry._
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
        n <- W.commit(s2) ++ W.emitO(s2.size)
      } yield n
    
    val avail: Process[Task,Availability[Int]] = halt 
    val j: Journal[Task,Set[Int],Availability[Int]] = dummyJournal 

    /* This `Process` will be persisted. */
    val numAvail: Process[Task,Int] = transaction(t)(avail)(j)

    true
  }
}
