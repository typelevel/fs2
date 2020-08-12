// package fs2

// import cats.Eq
// import cats.effect.IO
// import cats.effect.laws.discipline.arbitrary._
// import cats.effect.laws.util.TestContext
// import cats.effect.laws.util.TestInstances._
// import cats.implicits._
// import cats.laws.discipline._
// import cats.laws.discipline.arbitrary._

// class StreamLawsSuite extends Fs2Suite {
//   implicit val ec: TestContext = TestContext()

//   implicit def eqStream[O: Eq]: Eq[Stream[IO, O]] =
//     Eq.instance((x, y) =>
//       Eq[IO[Vector[Either[Throwable, O]]]]
//         .eqv(x.attempt.compile.toVector, y.attempt.compile.toVector)
//     )

//   checkAll(
//     "MonadError[Stream[F, *], Throwable]",
//     MonadErrorTests[Stream[IO, *], Throwable].monadError[Int, Int, Int]
//   )
//   checkAll(
//     "FunctorFilter[Stream[F, *]]",
//     FunctorFilterTests[Stream[IO, *]].functorFilter[String, Int, Int]
//   )
//   checkAll("MonoidK[Stream[F, *]]", MonoidKTests[Stream[IO, *]].monoidK[Int])
//   checkAll("Defer[Stream[F, *]]", DeferTests[Stream[IO, *]].defer[Int])
//   checkAll(
//     "Align[Stream[F, *]]",
//     AlignTests[Stream[IO, *]].align[Int, Int, Int, Int]
//   )
// }
