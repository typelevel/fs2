// package fs2
// package async
//
// import cats.effect.IO
//
// class QueueSpec extends Fs2Spec {
//   "Queue" - {
//     "unbounded producer/consumer" in {
//       forAll { (s: PureStream[Int]) =>
//         withClue(s.tag) {
//           runLog(Stream.eval(async.unboundedQueue[IO,Int]).flatMap { q =>
//             q.dequeue.merge(s.get.evalMap(q.enqueue1).drain).take(s.get.toVector.size)
//           }) shouldBe s.get.toVector
//         }
//       }
//     }
//     "circularBuffer" in {
//       forAll { (s: PureStream[Int], maxSize: SmallPositive) =>
//         withClue(s.tag) {
//           runLog(Stream.eval(async.circularBuffer[IO,Option[Int]](maxSize.get + 1)).flatMap { q =>
//             s.get.noneTerminate.evalMap(q.enqueue1).drain ++ q.dequeue.through(pipe.unNoneTerminate)
//           }) shouldBe s.get.toVector.takeRight(maxSize.get)
//         }
//       }
//     }
//     "dequeueAvailable" in {
//       forAll { (s: PureStream[Int]) =>
//         withClue(s.tag) {
//           val result = runLog(Stream.eval(async.unboundedQueue[IO,Option[Int]]).flatMap { q =>
//             s.get.noneTerminate.evalMap(q.enqueue1).drain ++ q.dequeueAvailable.through(pipe.unNoneTerminate).chunks
//           })
//           result.size should be < 2
//           result.flatMap(_.toVector) shouldBe s.get.toVector
//         }
//       }
//     }
//     "dequeueBatch unbounded" in {
//       forAll { (s: PureStream[Int], batchSize: SmallPositive) =>
//         withClue(s.tag) {
//           runLog(Stream.eval(async.unboundedQueue[IO,Option[Int]]).flatMap { q =>
//             s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream.constant(batchSize.get).through(q.dequeueBatch).through(pipe.unNoneTerminate)
//           }) shouldBe s.get.toVector
//         }
//       }
//     }
//     "dequeueBatch circularBuffer" in {
//       forAll { (s: PureStream[Int], maxSize: SmallPositive, batchSize: SmallPositive) =>
//         withClue(s.tag) {
//           runLog(Stream.eval(async.circularBuffer[IO,Option[Int]](maxSize.get + 1)).flatMap { q =>
//             s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream.constant(batchSize.get).through(q.dequeueBatch).through(pipe.unNoneTerminate)
//           }) shouldBe s.get.toVector.takeRight(maxSize.get)
//         }
//       }
//     }
//   }
// }
