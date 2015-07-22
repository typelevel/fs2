package streams

import java.util.UUID

private[streams]
class Finalizers[F[_]](private[Finalizers] val order: Vector[UUID],
                       private[Finalizers] val actions: Map[UUID, F[Unit]]) {

  def isEmpty = order.isEmpty

  def append(f2: Finalizers[F]): Finalizers[F] =
    if (order.isEmpty) f2
    else new Finalizers(order ++ f2.order, actions ++ f2.actions)

  def runDeactivated(f2: Finalizers[F]): Free[F,Unit] = {
    // anything which has dropped out of scope is considered deactivated
    val order2 = order.filter(id => !f2.actions.contains(id))
    order2.foldRight(Free.Pure(()): Free[F,Unit])((f,acc) => Free.Eval(actions(f)) flatMap { _ => acc })
  }

  def run: Free[F,Unit] =
    order.foldRight(Free.Pure(()): Free[F,Unit])((f,acc) => Free.Eval(actions(f)) flatMap { _ => acc })

  def size: Int = order.size

  override def toString = order.mkString("\n")
}

object Finalizers {
  def single[F[_]](f: F[Unit]): Finalizers[F] = {
    val id = UUID.randomUUID
    new Finalizers(Vector(id), Map(id -> f))
  }
  def empty[F[_]]: Finalizers[F] = new Finalizers(Vector(), Map())
}
