package scalaz.stream

import scalaz._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.\/._

import org.scalacheck._
import Prop._

import scalaz.concurrent.{Actor, Task}
import scalaz.stream.Process.End

import java.lang.Exception
import scala.Some
import scalaz.\/-
import scala.concurrent.SyncVar

import scala.collection.JavaConverters._
import scalaz.stream.message.ref.Fail

object ActorSpec extends Properties("actor") {
  
  property("queue") = forAll { l: List[Int] => 
    val (q, s) = actor.queue[Int]
    import message.queue._
    val t1 = Task { 
      l.foreach(i => q ! enqueue(i))
      q ! close
    }
    val t2 = s.collect

    Nondeterminism[Task].both(t1, t2).run._2.toList == l
  }
 
  case object TestedEx extends Exception("expected in test") {
    override def fillInStackTrace = this
  }
  
  def awaitClose[A](a:Actor[message.ref.Msg[A]]) : Option[Throwable] = {
    Task.async[Option[Throwable]] { cb =>
      a ! Fail(End, t=> cb(\/-(Some(t))))
    }.run
  }
            
  //initial basic continuous  stream test
  property("ref.basic") = forAll { l: List[Int] =>
    val (v, s:Process[Task,Int]) = actor.ref[Int]
    import message.ref._

    @volatile var read = Vector[(Int,Option[Int])]()
    @volatile var calledBack =  Vector[(Int,Throwable \/ Option[Int])]()
    @volatile var failCallBack :Option[Throwable] = None

    val t1:Task[Unit] = Task {
      l.foreach { i => v ! Set(r => { read = read :+ (i,r); Some(i)}, cbv => {calledBack = calledBack :+ (i,cbv); cbv.leftMap(t=>t.printStackTrace()) },false); Thread.sleep(1) }
      failCallBack = awaitClose(v)
    }

    val t2 = Task.fork(s.takeWhile(_ % 23 != 0).collect)

   
   val res = Nondeterminism[Task].both(t1, t2).run._2.toList

    
    (res.forall(_ % 23 != 0))                             :| "Stream  got some elements"  &&
    ((l zip (None +:l.map(Some(_)))) == read.toList)      :| "Initial values are ok" &&
    ((l zip l.map(v=>right(Some(v)))) == calledBack)      :| "CallBacks are ok" &&
    (failCallBack == Some(End))                           :| "FailCallBack is ok"
  }

   

  //checks that once terminated it will not feed anything else to process
  property("ref.stop-on-fail") = forAll { l: List[Int] =>
    val (v, s) = actor.ref[Int]
    import message.ref._

    val include = l.takeWhile(_ % 23 != 0)
    val exclude = l.filterNot(include.contains(_))

    @volatile var read = Vector[(Int,Option[Int])]()
    @volatile var calledBack =  Vector[(Int,Throwable \/ Option[Int])]()
    @volatile var failCallBack  = Vector[Throwable]()

    val t1 = Task {
      def feed(i:Int) = {  v ! Set(r => { read = read :+ (i,r); Some(i)}, cbv => (calledBack = calledBack :+ (i,cbv)),false); Thread.sleep(1) }
      def recordFailCb(t:Throwable) = (failCallBack = failCallBack :+ t)
      include.foreach(feed)
      v ! Fail(TestedEx, recordFailCb)
      exclude.foreach(feed)
      val last = awaitClose(v).toSeq 
      failCallBack = failCallBack ++ last.toSeq
    }

    val t2 = Task.fork(s.attempt().collect)
    val res = Nondeterminism[Task].both(t1, t2).run._2.toList

    res.collect{case \/-(r) => r}.forall(e=> ! exclude.contains(e))        :| "Stream was fed before End" &&
      (res.lastOption == Some(-\/(TestedEx)))                              :| "Last collected is Exception"  &&
      ((include zip (None +: include.map(Some(_)))) == read.toList)        :| "Initial values are ok" &&
      (((include zip include.map(v=>right(Some(v)))) ++
        (exclude zip exclude.map(v=>left(TestedEx))) == calledBack))       :| "CallBacks are ok"
    (failCallBack == Vector(TestedEx,TestedEx))                            :| "FailCallBacks are ok"
  }
   

  //checks it would never emit if the Set `f` would result in None
  property("ref.no-set-behaviour") = forAll { l: List[Int] =>
    val (v, s) = actor.ref[Int]
    import message.ref._

    @volatile var read = Vector[(Int,Option[Int])]()
    @volatile var calledBack =  Vector[(Int,Throwable \/ Option[Int])]()
    @volatile var failCallBack :Option[Throwable] = None

    val t1 = Task {
      l.foreach { i => v ! Set(r => { read = read :+ (i,r); None}, cbv => (calledBack = calledBack :+ (i,cbv)),false); Thread.sleep(1) }
      failCallBack = awaitClose(v)
    }

    val t2 = s.takeWhile(_ % 23 != 0).collect


    val res = Nondeterminism[Task].both(t1, t2).run._2.toList

    (res.size == 0)                                    :| "Stream is empty"    &&
      ((l zip (l.map(_=> None))) == read.toList)       :| "Initial values are ok" &&
      ((l zip l.map(_=>right(None))) == calledBack)    :| "CallBacks are ok"  &&
      (failCallBack == Some(End))                      :| "FailCallBack is ok"
  }
  
   
  //checks get only when changed. Like Get, only it will wait always for value to change.
  //will also test if the None is result of Set(f,_,_) it would not et the value of ref
  // odd values are == None, even values are set. 
  property("ref.get-when-changed")  = forAll { l: List[Int] =>
    val (v, _) = actor.ref[Int]
    import message.ref._
   
  
    @volatile var currentSerial = 0
    @volatile var serialChanges = Vector[(Int,Int)]()
    val s = Process.repeatWrap { Task.async[Int] { cb => v ! Get(cbv => cb(cbv.map(e =>{ 
      serialChanges = serialChanges :+ (currentSerial, e._1 )
      currentSerial = e._1; e._2 
    })),true,currentSerial) } }


    val feed:List[Int] = l.distinct.sorted

    @volatile var read = Vector[(Int,Option[Int])]()
    @volatile var calledBack =  Vector[(Int,Throwable \/ Option[Int])]()
    @volatile var failCallBack :Option[Throwable] = None

    val t1 = Task[Unit] {
      feed.foreach {  
        case i if i % 2 == 0 =>  v ! Set(r=> {read = read :+ (i,r); Some(i)}, cbv => (calledBack = calledBack :+ (i,cbv)),false) ; Thread.sleep(1)
        case i =>  v ! Set(r=> {read = read :+ (i,r); None}, cbv => (calledBack = calledBack :+ (i,cbv)),false) ; Thread.sleep(1)
      }
      failCallBack = awaitClose(v)
    }

    val t2 = Task.fork(s.collect)

    val res = Nondeterminism[Task].both(t1, t2).run._2.toList


    //when `f` returns None (i is odd) next must see previous value
    val (_,expectedRead) =
      feed.foldLeft[(Option[Int],List[(Int,Option[Int])])]((None,Nil)){
        case ((lastE,acc),i) if i %2 == 0 => (Some(i), acc :+ (i,lastE)) 
        case ((lastE,acc),i) => (lastE,acc :+ (i,lastE))

      }

    //when `f` returns None (i is odd), the callback must see previous value, otherwise must be returned by `f` 
    val (_, expectedCallBack) =
      feed.foldLeft[(Option[Int],List[(Int,Throwable \/ Option[Int])])]((None,Nil)){ 
        case ((lastE,acc),i) if i %2 == 0 => (Some(i), acc :+ (i,right(Some(i))))
        case ((lastE,acc),i) => (lastE,acc :+ (i,right(lastE)))
      }
  

    (res == res.filter(_ % 2 == 0).distinct.sorted)       :| "Stream has correct items (even only, distinct, sorted)"  &&
      (serialChanges.filter(e=>(e._1 == e._2)).isEmpty)   :| "Get did not return with same serial" &&
      (expectedRead == read.toList)                       :| "Initial values are ok"  &&
      (expectedCallBack == calledBack.toList)             :| "CallBacks are ok" &&
      (failCallBack == Some(End))                         :| "FailCallBack is ok"
  }
  
 
  //checks it catch the exception on set and will `fail` the ref
  property("ref.catch-ex-on-set") = forAll { l: List[Int] =>
    val (v, s) = actor.ref[Int]
    import message.ref._

    val include = l.filter (_ % 2 == 0)
    val exclude = l.filterNot(_ %2 == 0)

    @volatile var read = Vector[(Int,Option[Int])]()
    @volatile var calledBack =  Vector[(Int,Throwable \/ Option[Int])]()
    @volatile var onFailRead : Option[Option[Int]] = None
    @volatile var onFailCallBack : Option[Throwable \/ Option[Int]] = None
    @volatile var endFailCallBack  : Option[Throwable] = None

    val t1 = Task {
      def feed(i:Int) = {  v ! Set(r => { read = read :+ (i,r); Some(i)}, cbv => (calledBack = calledBack :+ (i,cbv)),false); Thread.sleep(1) }
      include.foreach(feed)
      v ! Set(r => { onFailRead = Some(r); throw TestedEx}, cbv => onFailCallBack = Some(cbv),false); Thread.sleep(1)
      exclude.foreach(feed)
      endFailCallBack = awaitClose(v)
    }

    val t2 = Task.fork(s.attempt().collect)
    val res = Nondeterminism[Task].both(t1, t2).run._2.toList
 
    
    res.collect{case \/-(r) => r}.forall(e=> ! exclude.contains(e))        :| "Stream was fed before exception" &&
      (res.lastOption == Some(-\/(TestedEx)))                                :| "Last collected is Exception"  &&
      ((include zip (None +: include.map(Some(_)))) == read.toList)          :| "Initial values are ok" &&
      (((include zip include.map(v=>right(Some(v)))) ++
        (exclude zip exclude.map(v=>left(TestedEx))) == calledBack))         :| "CallBacks are ok"  &&
      (onFailRead == Some(include.lastOption))                               :| "Fail read value is ok"    &&
      (onFailCallBack == Some(left(TestedEx)))                               :| "Fail callBack is ok"      &&
      (endFailCallBack == Some(TestedEx))                                    :| "End FailCallBack is ok"
  }
  
}
