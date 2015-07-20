package streams

case class Step[+A,+B](head: A, tail: B)

