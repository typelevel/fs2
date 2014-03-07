package scalaz.stream2

import Process._
import org.scalacheck.Properties
import org.scalacheck.Prop._

import scalaz.syntax.equal._
import scalaz.concurrent.Task

/**
 * Created by pach on 07/03/14.
 */
object ProcessThrowableHandlingSpec extends Properties("Throwable handling") {

  // Tests that exceptions in pure code is handled correctly
  // That in turns would mean that cleanup code will get handled
  // correctly


  val dieEx = new java.lang.Exception("bang!")

  def die = throw dieEx

  property("flatMap") = secure {

    println(emit(1).flatMap(_ => die) )

    Seq(
      "Emit" |: emit(1).flatMap(_ => die) == fail(dieEx)
  //    , "Append" |: (emit(1) ++ emit(2)).flatMap(_ => die) ==  fail(dieEx) ++
//      , "Append-die" |: (emit(1) ++ emit(2)).flatMap({ case 1 => emit(1) ; case 2 => die }) ==  fail(dieEx)
//      , "Await" |: await(Task.delay(1))(_.fold(fail,emit)).flatMap(_=> die) ==  fail(dieEx)
    ).reduce(_ && _)


  }

}
