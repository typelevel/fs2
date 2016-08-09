
### <a id="gadts"> Encoding of GADTs in Scala

Scala does not currently support GADTs well at all. We use some unusual workarounds which will be explained here.

First, the problem. In Haskell, we'd write and use a GADT like so:

```Haskell
data Chain f a b where
  Empty :: Chain f a a
  Cons :: f a x -> Chain f x b -> Chain f a b

interpret :: (forall a b . f a b -> a -> b) -> Chain f a b -> a -> b
interpret _  Empty        = id
interpret fi (Cons hd tl) = interpret tl . fi hd
```

In the definition of `interpret`, pattern matching on `Empty` brings into scope the equality `a ~ b` (since the type of `Empty` has type `Chain k a a`). Thus, `id : a -> a` is considered to match the type `a -> b`.

If we try to encode this directly in Scala, we hit several problems:

```Scala
trait Chain[F[_,_],A,B]

object Chain {
  case class Empty[F[_,_],A]() extends Chain[F,A,A]
  case class Cons[F[_,_],A,x,B](hd: F[A,x], tl: Chain[F,x,B]) extends Chain[F,A,B]
}
```

The first, which we'll ignore for a minute, is that Scala does not support higher-rank types. So we can't just write the type `forall a b . K[a,b] => (a => b)` in Scala. The bigger problem is that pattern matching on `Chain` values rarely works. If `K` is a type lambda like `({ type f[a,b] = a => Stream[F,b] })#f`, Scala won't even let you write the pattern `Empty` or `Cons` (try it). And even if `K` is not a type lambda, you'll hit all sorts of weird bugs.

#### The workaround

We workaround these issues by representing GADTs via their fold, and hand-supplying any needed equalities:

```Scala
sealed trait Chain[F[_,_],A,B] { self =>
  def apply[R](empty: (A => B, B => A) => R, cons: H[R]): R
  trait H[+R] { def f[x]: (F[A,x], Chain[F,x,B]) => R }
}
object Chain {
  def cons[F[_,_],A,x,B](head: F[A,x], tail: Chain[F,x,B]) = new Chain[F,A,B] {
    def apply[R](empty: (A => B, B => A) => R, cons: H[R]): R =
      cons.f(head, tail)
  }

  def empty[F[_,_],A]: Chain[F,A,A] = new Chain[F,A,A] {
    def apply[R](empty: (A => A, A => A) => R, cons: H[R]): R =
      empty(identity, identity)
  }
}
```

Notice `Chain` just has a function `apply` which is equivalent to a 1-step pattern match. We supply two handlers, one which is invoked if the `Chain` is empty, and one which is invoked if the chain is a `cons`. If the chain is empty, the handler obtains a "proof" that `A == B` in the form of a pair of functions from `A => B` and `B => A` (there are other better ways to encode this proof, but this is very simple). If the chain is non-empty, we have an existential `x` and the handler must be universal in that type parameter. Here's an example of this in use:

```Scala
// k : Chain[F,A,B]
k (
  // empty case doesn't need the equality proof
  (_,_) => runCleanup(tracked) map (_ => Right(z)),
  new k.H[Free[F2,Either[Throwable,O]]] { def f[x] =
    (kh,k) => ...
  }
)

/* What we wish we could write
k match {
  case Empty() => runCleanup(tracked) map (_ => Right(z))
  case Cons(kh,k) => ...
}
*/
```

If you squint, it kinda sorta looks like regular pattern matching. Right? (Okay, not really.)

You'll see this technique used throughout the library.
