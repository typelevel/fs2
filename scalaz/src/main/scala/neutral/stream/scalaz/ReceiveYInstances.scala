package neutral.stream.scalaz

import neutral.stream.ReceiveY
import neutral.stream.ReceiveY.{HaltOne, ReceiveL, ReceiveR}

import scalaz.{Monad, Monoid, Equal}

trait ReceiveYInstances {
  implicit def receiveYequal[X, Y](implicit X: Equal[X], Y: Equal[Y]): Equal[ReceiveY[X, Y]] =
    Equal.equal{
      case (ReceiveL(a), ReceiveL(b)) => X.equal(a, b)
      case (ReceiveR(a), ReceiveR(b)) => Y.equal(a, b)
      case _ => false
    }

  implicit def receiveYInstance[X](implicit X: Monoid[X]) =
    new Monad[({type f[y] = ReceiveY[X,y]})#f] {
      def point[Y](x: => Y): ReceiveY[X,Y] = ReceiveR(x)
      def bind[Y,Y2](t: ReceiveY[X,Y])(f: Y => ReceiveY[X,Y2]): ReceiveY[X,Y2] =
        t match {
          case a@ReceiveL(_) => a
          case ReceiveR(x) => f(x)
          case h:HaltOne => h
        }
    }
}

