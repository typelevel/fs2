package scalaz.stream2

import org.scalacheck.Properties
import org.scalacheck.Prop._
import Process._
import scalaz.stream2.process1._

/**
 * Created by pach on 07/03/14.
 */
object ExperimentSpec extends Properties("Experiments") {

  property("here") = secure {

    val pl = List(1)


    //val pl2 = 0 until 10000
     val p = Process(pl:_*)

    val pf : PartialFunction[Int,Int] = { case x : Int if x % 2 == 1 => x}

//    val f = (x: Int) => List.range(1, x.min(100))
//
//   val p = Emit(-1,List(-1, -338840612, -404450079, 2147483647, -1971109425, 0, 1, -2001298503, -2147483648, 1))
//
//    println("##########"*10 + p)
//    println("P1 " + p.toList.flatMap(f).size)
//    println("P2 " + p.flatMap(f andThen Process.emitAll).toList.size )

   // "map-pipe" |: (p.map(_ + 1).toList == p.pipe(lift(_ + 1)).toList)

    val pi = Process((0 until 3).toSeq:_*)
    val li = pi.toList

//    val pp1 = process1.id[Int] |> process1.id[Int]
//    println(pp1)
//   println("@##########################"*10)
//
//    val fed =  process1.feed(Seq(99))(pp1)
//     println(("@@@@", fed, fed.unemit._1))



 println(s"################### buffer: $li ${pi.pipe(process1.chunk(4)).toList}")

//   TestUtil.time( pi.pipe(process1.id).toList == li)
//     TestUtil.time( pi.pipe(process1.buffer(4)).toList == li)
 // pi.pipe(process1.buffer(4)).toList == li
false

  }

}
