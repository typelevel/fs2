package scalaz.stream

import org.scalacheck._
import scalaz.concurrent.Task
import scalaz.scalacheck.ScalazProperties._
import scalaz.std.anyVal._

import TestInstances._

class ProcessInstancesSpec extends Properties("ProcessInstances") {

  type ProcessF[F[_]] = ({ type l[a] = Process[F, a] })
  type Process1O[O] = ({ type l[i] = Process1[i, O] })

  // These properties fail with scalaz prior to 7.1.0 with:
  //   Exception raised on property evaluation.
  //   Exception: java.lang.NoClassDefFoundError: org/scalacheck/Pretty$
  // They should be enabled when switching to scalaz >= 7.1.0.

  //property("Process.monadPlus.laws") = monadPlus.laws[ProcessF[Task]#l]

  //property("Process1.category.laws") = category.laws[Process1]

  //property("Process1.contravariant.laws") = contravariant.laws[Process1O[Int]#l]

}
