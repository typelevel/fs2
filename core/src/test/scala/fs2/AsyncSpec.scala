package fs2

import fs2.util.Task
import TestUtil._
import org.scalacheck._
import Prop._

object AsyncSpec extends Properties("Async") {

  val F = implicitly[Async[Task]]

  property("async success") = protect {
    val t = F.async[Int] { cb => cb(Right(42)) }
    t.run ?= 42
  }

  property("async failure") = protect {
    val t = F.async[Int] { cb => cb(Left(Err)) }
    val t2 = t.flatMap(_ => F.pure(10)).flatMap(_ => F.pure(11))
    (try { t.run; false } catch { case Err => true }) &&
    (try { t2.run; false } catch { case Err => true })
  }
}
