package scalaz.stream

import scalaz.concurrent.Task
import scalaz._
import scalaz.\/._
import Process._

import org.scalacheck._
import Prop._

object JournaledStreams extends Properties("journaled-streams") {

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

  property("transaction") = secure {
    
    val W = Writer
    import W.Transaction
    
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
    
    val avail: Process[Task,Availability[Int]] = ???     
    val j: Journal[Task,Set[Int],Availability[Int]] = ???

    /* This `Process` will be persisted. */
    val numAvail: Process[Task,Int] = 
      W.transaction(t)(avail)(j)

    true
  }
}
