package object fs2 {

  @deprecated("renamed to fs2.Stream", "0.9")
  type Process[+F[_],+O] = Stream[F,O]
  @deprecated("renamed to fs2.Stream", "0.9")
  val Process = Stream
}
