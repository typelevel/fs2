package scalaz.concurrent

import scala.annotation.unchecked.uncheckedVariance

trait Task[+A] {
  def run: A
  def attempt: Task[Either[Throwable, A]]
  def runAsync(f: Either[Throwable, A] => Unit): Unit
  def map[B](f: A => B): Task[B]
  def flatMap[B](f: A => Task[B]): Task[B]
}

object Task {
  def now[A](a: A): Task[A] = ???
  def delay[A](a: => A): Task[A] = ???
  def suspend[A](a: => Task[A]): Task[A] = ???
  def async[A](cb: (Either[Throwable, A] => Unit) => Unit): Task[A] = ???
  def fail[A](e: Throwable): Task[A] = ???
}
