package fs2

object ThisModuleShouldCompile {

  val purePull = Stream(1,2,3,4) pull process1.pull.take(2)
  val x = process1.pull.take(2)
  val pureAppend = Stream(1,2,3) ++ Stream(4,5,6)
}

