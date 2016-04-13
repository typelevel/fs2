package fs2.io.tcp


import fs2.concurrent
import fs2.util.Task
import org.scalacheck.Properties

import org.scalacheck.Prop._
import fs2.io.TestUtil._
import fs2.TestUtil._
import fs2.Stream._

/**
  * Created by pach on 10/04/16.
  */
object SocketSpec extends Properties("tcp.Socket") {

  implicit val agGroup : GroupName = GroupName("tcp")

  println(s">>> BINDING TO:  $localBindAddress")

  // spawns echo server, then runs requests against it and
  // verifies that all has been read and written.
  property("echo.requests") = protect { acquireLock {



    concurrent.join(Int.MaxValue)(
      server[Task](localBindAddress)
      .map { cx =>
        cx.flatMap { (socket :Socket[Task]) =>
          socket.writes(socket.reads(1024))
          .onComplete(eval_(socket.close))
        }
      }
    ).run.run.run

    false
  } }


}
