# Ecosystem

### Typelevel Dependencies

As described in the `README` file, FS2 is based mostly on `cats` and `cats-effect`.

* The `cats` library provides definitions for the typeclasses `Functor`, `Applicative`, `Monad`, and `MonadError`.
* FS2 also uses the data type [`cats.data.Chain`](https://typelevel.org/cats/datatypes/chain.html) from the `cats-core` module. This data structure implements an ordered list-like sequence as a catenable list, which allows for efficient insertion at both ends. This data type was originally developed in `fs2`, where it was called `Catenable`.
* The `cats-effect` library extends the typeclasses from `cats` with other more specialised typeclasses, which describe effectful computations. These type-classes cover resource  bracketing, concurrency, asynchronicity, input-output, timing, and interruption.
* FS2 also uses from `cats-effect` several data structures used for communicating  and coordinating concurrent processes, such as [semaphores](https://typelevel.org/cats-effect/concurrency/semaphore.html), [deferred values](https://typelevel.org/cats-effect/concurrency/deferred.html), [atomic references](https://typelevel.org/cats-effect/concurrency/ref.html). Some of these types were first developed as part of the internal implementation of FS2, but then moved to `cats-effect`.
* From [scodec-bits](https://github.com/scodec/scodec-bits), FS2 uses the `ByteVector` data type to implement bytevector chunks. This is relevant for efficiently processing streams of binary data.

### Libraries using FS2, and integrations with data stores

If you have a project you'd like to include in this list, either [click here](https://github.com/typelevel/fs2/edit/main/site/ecosystem.md) or let us know in [the gitter channel](https://gitter.im/functional-streams-for-scala/fs2) and we'll add it for you.

* [cabbit](https://github.com/delimobil/cabbit): Simple client for RabbitMQ using fs2 and cats-effect.
* [canoe](https://github.com/augustjune/canoe): A purely functional library for building Telegram chatbots.
* [chromaprint](https://github.com/mgdigital/Chromaprint.scala): A Scala implementation of the Chromaprint/AcoustID audio fingerprinting algorithm, built with fs2 streams / Cats Effect.
* [circe-fs2](https://github.com/circe/circe-fs2): Streaming JSON manipulation with [circe](https://github.com/circe/circe).
* [doobie](https://github.com/tpolecat/doobie): Pure functional JDBC built on fs2.
* [fs2-aes](https://github.com/jwojnowski/fs2-aes): AES encryption for fs2.
* [fs2-aws](https://github.com/saksdirect/fs2-aws): FS2 streams to interact with AWS utilities
* [fs2-blobstore](https://github.com/fs2-blobstore/fs2-blobstore): Minimal, idiomatic, stream-based Scala interface for S3, GCS, SFTP and other key/value store implementations.
* [fs2-cassandra](https://github.com/Spinoco/fs2-cassandra): Cassandra bindings for fs2.
* [fs2-columns](https://gitlab.com/lJoublanc/fs2-columns): a `Chunk` that uses [shapeless](https://github.com/milessabin/shapeless) to store `case class` data column-wise.
* [fs2-cron](https://github.com/fthomas/fs2-cron): FS2 streams based on cron expressions.
* [fs2-crypto](https://github.com/Spinoco/fs2-crypto): TLS support for fs2.
* [fs2-data](https://github.com/satabin/fs2-data): A set of data parsers and manipulation libraries for various formats for fs2.
* [fs2-elastic](https://github.com/amarrella/fs2-elastic): Simple client for Elasticsearch.
* [fs2-es](https://sloshy.github.io/fs2-es/): Event-sourcing and event-driven state management utilities using FS2.
* [fs2-ftp](https://github.com/regis-leray/fs2-ftp): Simple client for Ftp/Ftps/Sftp using fs2 and cats-effect.
* [fs2-google-pubsub](https://github.com/permutive/fs2-google-pubsub): A [Google Cloud Pub/Sub](https://cloud.google.com/pubsub/) implementation using fs2 and cats-effect.
* [fs2-grpc](https://github.com/typelevel/fs2-grpc): gRPC implementation for FS2 / Cats Effect.
* [fs2-http](https://github.com/Spinoco/fs2-http): Http server and client library implemented in fs2.
* [fs2-jms](https://github.com/kiambogo/fs2-jms): Java Messaging Service (JMS) connectors for FS2 streams
* Apache Kafka integrations
  * [kafka4s](https://github.com/Banno/kafka4s): Functional programming with Kafka and Scala.
  * [fd4s/fs2-kafka](https://github.com/fd4s/fs2-kafka): Functional Kafka Streams for Scala.
  * [spinoco/fs2-kafka](https://github.com/Spinoco/fs2-kafka): Simple client for Apache Kafka.
* [fs2-mail](https://github.com/Spinoco/fs2-mail): Fully asynchronous java non-blocking email client using fs2.
* [fs2-mqtt](https://github.com/user-signal/fs2-mqtt): A purely functional MQTT client library based on fs2 and cats effect.
* [fs2-rabbit](https://fs2-rabbit.profunktor.dev/): RabbitMQ stream-based client built on top of fs2 and cats effect.
* [fs2-reactive-streams](https://github.com/zainab-ali/fs2-reactive-streams): A reactive streams implementation for fs2.
* [fs2-ssh](https://github.com/slamdata/fs2-ssh): A wrapper around Apache SSHD targeting cats-effect and fs2.
* [fs2-throttler](https://github.com/kovstas/fs2-throttler): Throttling for fs2 based on the token bucket algorithm.
* [fs2-zk](https://github.com/Spinoco/fs2-zk): Simple Apache Zookeeper bindings for fs2.
* [http4s](http://http4s.org/): Minimal, idiomatic Scala interface for HTTP services using fs2.
* [Lepus](https://lepus.hnaderi.dev/): Purely functional, non-blocking RabbitMQ client for Scala, Scala.js and Scala Native (with complete support for AMQP 0.9.1 + RabbitMQ extensions)
* [mongo4cats](https://github.com/Kirill5k/mongo4cats): Mongo DB Scala client wrapper for Cats Effect & FS2.
* [mongosaur](https://gitlab.com/lJoublanc/mongosaur): fs2-based MongoDB driver.
* [mysql-binlog-stream](https://github.com/laserdisc-io/mysql-binlog-stream): Stream MySQL binlog events with FS2
* [neotypes](https://github.com/neotypes/neotypes): A Scala lightweight, type-safe & asynchronous driver for neo4j. Support streaming queries using fs2.
* [neutron](https://github.com/cr-org/neutron): Apache Pulsar bindings for fs2.
* [prox](https://vigoo.github.io/prox/): A library for working with system processes
* [pure-aws](https://github.com/rewards-network/pure-aws): Pure-functional AWS clients including FS2 streaming support.
* [redis4cats](https://redis4cats.profunktor.dev/): Redis client built on top of fs2 and cats effect.
* [scarctic](https://gitlab.com/lJoublanc/scarctic): fs2-based driver for [MAN/AHL's Arctic](https://github.com/manahl/arctic) data store.
* [scodec-protocols](https://github.com/scodec/scodec-protocols): A library for working with libpcap files. Contains many interesting pipes (e.g., working with time series and playing back streams at various rates).
* [scodec-stream](https://github.com/scodec/scodec-stream): A library for streaming binary decoding and encoding, built using fs2 and [scodec](https://github.com/scodec/scodec).
* [spata](https://github.com/fingo/spata): CSV parser build on FS2.
* [sqs4s](https://github.com/d2a4u/sqs4s): A pure Scala client for AWS SQS using fs2.
* [streamz](https://github.com/krasserm/streamz): A library that supports the conversion of [Akka Stream](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) `Source`s, `Flow`s and `Sink`s to and from FS2 `Stream`s, `Pipe`s and `Sink`s, respectively. It also supports the usage of [Apache Camel](http://camel.apache.org/) endpoints in FS2 `Stream`s and Akka Stream `Source`s, `Flow`s and `SubFlow`s.
* [upperbound](https://github.com/SystemFw/upperbound): A purely functional, interval-based rate limiter with support for backpressure.
* [vinyldns](https://github.com/vinyldns/vinyldns): A DNS governance system using fs2 for throttling updates to DNS backends.

Don't see your project on the list? [Click here](https://github.com/typelevel/fs2/edit/main/site/ecosystem.md) to add it!
