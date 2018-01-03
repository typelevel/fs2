package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

@State(Scope.Thread)
class CatenableBenchmark {

  val smallCatenable = Catenable(1, 2, 3, 4, 5)
  val smallVector = Vector(1, 2, 3, 4, 5)

  val largeCatenable = Catenable.fromSeq(0 to 1000000)
  val largeVector = (0 to 1000000).toVector

  @Benchmark def mapSmallCatenable = smallCatenable.map(_ + 1)
  @Benchmark def mapSmallVector = smallVector.map(_ + 1)
  @Benchmark def mapLargeCatenable = largeCatenable.map(_ + 1)
  @Benchmark def mapLargeVector = largeVector.map(_ + 1)

  @Benchmark def foldLeftSmallCatenable = smallCatenable.foldLeft(0)(_ + _)
  @Benchmark def foldLeftSmallVector = smallVector.foldLeft(0)(_ + _)
  @Benchmark def foldLeftLargeCatenable = largeCatenable.foldLeft(0)(_ + _)
  @Benchmark def foldLeftLargeVector = largeVector.foldLeft(0)(_ + _)

  @Benchmark def consSmallCatenable = 0 +: smallCatenable
  @Benchmark def consSmallVector = 0 +: smallVector
  @Benchmark def consLargeCatenable = 0 +: largeCatenable
  @Benchmark def consLargeVector = 0 +: largeVector

  @Benchmark def createTinyCatenable = Catenable(1)
  @Benchmark def createTinyVector = Vector(1)
  @Benchmark def createSmallCatenable = Catenable(1, 2, 3, 4, 5)
  @Benchmark def createSmallVector = Vector(1, 2, 3, 4, 5)
}
