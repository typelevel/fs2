package fs2

import cats.effect.{IO, Resource}

object ResourceCompilationSpec {

  /** This should compile */
  val pure: List[Int] = Stream.range(0, 5).compile.toList
  val io: IO[List[Int]] = Stream.range(0, 5).covary[IO].compile.toList
  val resource: Resource[IO, List[Int]] = Stream.range(0, 5).covary[IO].compile.toResource.toList
}
