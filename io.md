# I/O

The `fs2-io` library provides support for performing input and output on the JVM, Node.js, and Scala Native. This includes:
- [Networking](#networking)
  - [TCP](#tcp)
  - [UDP](#udp)
  - [TLS](#tls)
- [Files](#files)
- [Processes](#processes)
- [Console operations](#console-operations)
- [Interop with `java.io.{InputStream, OutputStream}`](#java-stream-interop)

In this section, we'll look at each of these features.

# Networking

The `fs2-io` library supports both TCP and UDP via the `fs2.io.net` package. The `fs2.io.net` package provides purely functional abstractions on top of the Java NIO networking support. The package takes full advantage of the resource safety guarantees of cats-effect and `fs2.Stream`.

## TCP

The `fs2.io.net.Socket` trait provides mechanisms for reading and writing data -- both as individual actions and as part of stream programs.

### Clients

To get started, let's write a client program that connects to a server, sends a message, and reads a response.

```scala
import fs2.{Chunk, Stream}
import fs2.io.net.Network
import cats.effect.MonadCancelThrow
import cats.effect.std.Console
import cats.syntax.all._
import com.comcast.ip4s._

def client[F[_]: MonadCancelThrow: Console: Network]: F[Unit] =
  Network[F].client(SocketAddress(host"localhost", port"5555")).use { socket =>
    socket.write(Chunk.array("Hello, world!".getBytes)) >>
      socket.read(8192).flatMap { response =>
        Console[F].println(s"Response: $response")
      }
  }
```

To open a socket that's connected to `localhost:5555`, we use the `client` method on the `Network` capability. The `Network` capability provides the runtime environment for the sockets it creates.

The `Network[F].client` method returns a `Resource[F, Socket[F]]` which automatically closes the socket after the resource has been used. To write data to the socket, we call `socket.write`, which takes a `Chunk[Byte]` and returns an `F[Unit]`. Once the write completes, we do a single read from the socket via `socket.read`, passing the maximum amount of bytes we want to read. The returns an `F[Option[Chunk[Byte]]]` -- `None` if the socket reaches end of input and `Some` if the read produced a chunk. Finally, we print the response to the console.

Note we aren't doing any binary message framing or packetization in this example. Hence, it's very possible for the single read to only receive a portion of the original message -- perhaps just the bytes for `"Hello, w"`. We can use FS2 streams to simplify this. The `Socket` trait defines stream operations  -- `writes` and `reads`. We could rewrite this example using the stream operations like so:

```scala
import fs2.{Chunk, Stream, text}
import fs2.io.net.Network
import cats.effect.MonadCancelThrow
import cats.effect.std.Console
import cats.syntax.all._
import com.comcast.ip4s._

def client[F[_]: MonadCancelThrow: Console: Network]: Stream[F, Unit] =
  Stream.resource(Network[F].client(SocketAddress(host"localhost", port"5555"))).flatMap { socket =>
    Stream("Hello, world!")
      .through(text.utf8.encode)
      .through(socket.writes) ++
        socket.reads
          .through(text.utf8.decode)
          .foreach { response =>
            Console[F].println(s"Response: $response")
          }
  }
```

The structure changes a bit. First, the socket resource is immediately lifted in to a stream via `Stream.resource`. Second, we create a single `Stream[Pure, String]`, transform it with `text.utf8.encode` to turn it in to a `Stream[Pure, Byte]`, and then transform it again with `socket.writes` which turns it in to a `Stream[F, Unit]`. The `socket.writes` method returns a pipe that writes each underlying chunk of the input stream to the socket, giving us a `Stream[F, Nothing]`.

We then append a stream that reads a response -- we do this via `socket.reads`, which gives us a `Stream[F, Byte]` that terminates when the socket is closed or it receives an end of input indication. We transform that stream with `text.utf8.decode`, which gives us a `Stream[F, String]`. We then print each received response to the console.

This program won't end until the server side closes the socket or indicates there's no more data to be read. To fix this, we need a protocol that both the client and server agree on. Since we are working with text, let's use a simple protocol where each frame (or "packet" or "message") is terminated with a `\n`. We'll have to update both the write side and the read side of our client.

```scala
def client[F[_]: MonadCancelThrow: Console: Network]: Stream[F, Unit] =
  Stream.resource(Network[F].client(SocketAddress(host"localhost", port"5555"))).flatMap { socket =>
    Stream("Hello, world!")
      .interleave(Stream.constant("\n"))
      .through(text.utf8.encode)
      .through(socket.writes) ++
        socket.reads
          .through(text.utf8.decode)
          .through(text.lines)
          .head
          .foreach { response =>
            Console[F].println(s"Response: $response")
          }
  }
```

To update the write side, we added `.interleave(Stream.constant("\n"))` before doing UTF8 encoding. This results in every input string being followed by a `"\n"`. On the read side, we transformed the output of `utf8.decode` with `text.lines`, which emits the strings between newlines. Finally, we call `head` to take the first full line of output. Note that we discard the rest of the `reads` stream after processing the first full line. This results in the socket getting closed and cleaned up correctly.

#### Handling Connection Errors

If a TCP connection cannot be established, `socketGroup.client` fails with a `java.net.ConnectException`. To automatically attempt a reconnection, we can handle the `ConnectException` and try connecting again.

```scala
import scala.concurrent.duration._
import cats.effect.Temporal
import fs2.io.net.Socket
import java.net.ConnectException

def connect[F[_]: Temporal: Network](address: SocketAddress[Host]): Stream[F, Socket[F]] =
  Stream.resource(Network[F].client(address))
    .handleErrorWith {
      case _: ConnectException =>
        connect(address).delayBy(5.seconds)
    }

def client[F[_]: Temporal: Console: Network]: Stream[F, Unit] =
  connect(SocketAddress(host"localhost", port"5555")).flatMap { socket =>
    Stream("Hello, world!")
      .interleave(Stream.constant("\n"))
      .through(text.utf8.encode)
      .through(socket.writes) ++
        socket.reads
          .through(text.utf8.decode)
          .through(text.lines)
          .head
          .foreach { response =>
            Console[F].println(s"Response: $response")
          }
  }
```

We've extracted the `Network[IO].client` call in to a new method called `connect`. The connect method attempts to create a client and handles the `ConnectException`. Upon encountering the exception, we call `connect` recursively after a 5 second delay. Because we are using `delayBy`, we needed to add a `Temporal` constraint to `F`. This same pattern could be used for more advanced retry strategies -- e.g., exponential delays and failing after a fixed number of attempts. Streams that call methods on `Socket` can fail with exceptions due to loss of the underlying TCP connection. Such exceptions can be handled in a similar manner.

### Servers

Now let's implement a server application that communicates with the client app we just built. The server app will be a simple echo server -- for each line of text it receives, it will reply with the same line.

```scala
import cats.effect.Concurrent

def echoServer[F[_]: Concurrent: Network]: F[Unit] =
  Network[F].server(port = Some(port"5555")).map { client =>
    client.reads
      .through(text.utf8.decode)
      .through(text.lines)
      .interleave(Stream.constant("\n"))
      .through(text.utf8.encode)
      .through(client.writes)
      .handleErrorWith(_ => Stream.empty) // handle errors of client sockets
  }.parJoin(100).compile.drain
```

We start with a call to `Network[IO].server` which returns a value of an interesting type -- `Stream[F, Socket[F]]`. This is an infinite stream of client sockets -- each time a client connects to the server, a `Socket[F]` is emitted, allowing interaction with that client. The lifetime of the client socket is managed by the overall stream -- e.g. flat mapping over a socket will keep that socket open until the returned inner stream completes, at which point, the client socket is closed and any underlying resources are returned to the runtime environment.

We map over this infinite stream of clients and provide the logic for handling an individual client. In this case,
we read from the client socket, UTF-8 decode the received bytes, extract individual lines, and then write each line back to the client. This logic is implemented as a single `Stream[F, Unit]`.

Since we mapped over the infinite client stream, we end up with a `Stream[F, Stream[F, Unit]]`. We flatten this to a single `Stream[F, Unit]` via `parJoin(100)`, which runs up to 100 of the inner streams concurrently. As inner streams finish, new inner streams are pulled from the source. Hence, `parJoin` is controlling the maximum number of concurrent client requests our server processes.

In joining all these streams together, be prudent to handle errors in the client streams.

The pattern of `Network[F].server(address).map(handleClient).parJoin(maxConcurrentClients)` is very common when working with server sockets.

A simpler echo server could be implemented with this core logic:

```scala
client.reads.through(client.writes)
```

However, such an implementation would echo bytes back to the client as they are received instead of only echoing back full lines of text.

The [fs2-chat](https://github.com/functional-streams-for-scala/fs2-chat) sample application implements a multiuser chat server and a single user chat client using the FS2 TCP support and [scodec](https://scodec.org) for binary processing.

## UDP

UDP support works much the same way as TCP. The `fs2.io.net.DatagramSocket` trait provides mechanisms for reading and writing UDP datagrams. UDP sockets are created via the `openDatagramSocket` method on `fs2.io.net.Network`. Unlike TCP, there's no differentiation between client and server sockets. Additionally, since UDP is a packet based protocol, read and write operations use `fs2.io.net.Datagram` values, which consist of a `Chunk[Byte]` and a `SocketAddress[IpAddress]`.

Adapting the TCP client example for UDP gives us the following:

```scala
import fs2.{Stream, text}
import fs2.io.net.{Datagram, Network}
import cats.effect.Concurrent
import cats.effect.std.Console
import com.comcast.ip4s._

def client[F[_]: Concurrent: Console: Network]: F[Unit] = {
  val address = SocketAddress(ip"127.0.0.1", port"5555")
  Stream.resource(Network[F].openDatagramSocket()).flatMap { socket =>
    Stream("Hello, world!")
      .through(text.utf8.encode)
      .chunks
      .map(data => Datagram(address, data))
      .through(socket.writes)
      .drain ++
        socket.reads
          .flatMap(datagram => Stream.chunk(datagram.bytes))
          .through(text.utf8.decode)
          .foreach { response =>
            Console[F].println(s"Response: $response")
          }
  }.compile.drain
}
```

When writing, we map each chunk of bytes to a `Datagram`, which includes the destination address of the packet. When reading, we convert the `Stream[F, Datagram]` to a `Stream[F, Byte]` via `flatMap(datagram => Stream.chunk(datagram.bytes))`. Otherwise, the example is unchanged.

```scala
def echoServer[F[_]: Concurrent: Network]: F[Unit] =
  Stream.resource(Network[F].openDatagramSocket(port = Some(port"5555"))).flatMap { socket =>
    socket.reads.through(socket.writes)
  }.compile.drain
```

The UDP server implementation is much different than the TCP server implementation. We open a socket that's bound to port `5555`. We then read datagrams from that socket, writing each datagram back out. Since each received datagram has the remote address of the sender, we can reuse the same datagram for writing.

## TLS

The `fs2.io.net.tls` package provides support for TLS over TCP and DTLS over UDP, built on top of `javax.net.ssl` on JVM, [s2n-tls] on Scala Native, and the [`node:tls` module] on Node.js. TLS over TCP is provided by the `TLSSocket` trait, which is instantiated by `Network.tlsContext`. A `TLSContext` provides cryptographic material used in TLS session establishment -- e.g., the set of certificates that are trusted (sometimes referred to as a trust store) and optionally, the set of certificates identifying this application (sometimes referred to as a key store). The `TLSContext.Builder` trait provides many ways to construct a `TLSContext` -- for example:
- `system` - uses the platform-specific default trust store
  - delegates to `javax.net.ssl.SSLContext.getDefault` on JVM
- `fromKeyStoreFile(pathToFile, storePassword, keyPassword)` - loads a Java Key Store file (JVM-only)
- `insecure` - trusts all certificates - note: this is dangerously insecure - only use for quick tests

A `TLSContext` is typically created at application startup, via `Network[F].tlsContext`, and used for all sockets for the lifetime of the application. Once a `TLSContext` has been created, the `client` and `server` methods are used to create `TLSSocket` instances (and `dtlsClient` / `dtlsServer` methods for `DTLSSocket`). In each case, a regular socket must be provided, which the `TLSSocket` will use for performing the TLS handshake as well as transmitting and receiving encrypted data. `TLSSocket` extends `fs2.io.net.Socket`, making the addition of TLS support a drop in replacement for a program using `fs2-io`.

Adapting the TCP echo client for TLS looks like this:

```scala
import fs2.{Chunk, Stream, text}
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import cats.effect.MonadCancelThrow
import cats.effect.std.Console
import cats.syntax.all._
import com.comcast.ip4s._

def client[F[_]: MonadCancelThrow: Console: Network](
  tlsContext: TLSContext[F]): Stream[F, Unit] = {
  Stream.resource(Network[F].client(SocketAddress(host"localhost", port"5555"))).flatMap { underlyingSocket =>
    Stream.resource(tlsContext.client(underlyingSocket)).flatMap { socket =>
      Stream("Hello, world!")
        .interleave(Stream.constant("\n"))
        .through(text.utf8.encode)
        .through(socket.writes) ++
          socket.reads
            .through(text.utf8.decode)
            .through(text.lines)
            .head
            .foreach { response =>
              Console[F].println(s"Response: $response")
            }
    }
  }
}
```

The only difference is that we wrap the underlying socket with a `TLSSocket`.

### Configuring TLS Session Parameters

The various methods on `TLSContext` that create `TLSSocket`s and `DTLSSocket`s all take a `TLSParameters` argument, allowing session level configuration. This allows configuration of things like client authentication, supported protocols and cipher suites, and SNI extensions. For example:

```scala
import fs2.io.net.tls.{TLSParameters, TLSSocket}
import cats.effect.Resource
import javax.net.ssl.SNIHostName

def tlsClientWithSni[F[_]: MonadCancelThrow: Network](
  tlsContext: TLSContext[F],
  address: SocketAddress[Host]): Resource[F, TLSSocket[F]] =
  Network[F].client(address).flatMap { underlyingSocket =>
    tlsContext.clientBuilder(
      underlyingSocket
    ).withParameters(
      TLSParameters(
        protocols = Some(List("TLSv1.3")),
        serverNames = Some(List(new SNIHostName(address.host.toString)))
      )
    ).build
  }
```

In this example, we've configured the TLS session to require TLS 1.3 and we've added an SNI extension with the hostname of the target server.

### Accessing TLS Session Information

`TLSSocket` extends `Socket` and provides a few additional methods. One particularly interesting method is `session`, which returns a `F[javax.net.ssl.SSLSession]`, containing information about the established TLS session. This allows us to query for things like the peer certificate or the cipher suite that was negotiated.

In the following example, we extract various information about the session, in order to help debug TLS connections.

```scala
def debug[F[_]: MonadCancelThrow: Network](
    tlsContext: TLSContext[F],
    address: SocketAddress[Host]
): F[String] =
  Network[F].client(address).use { underlyingSocket =>
    tlsContext
      .clientBuilder(underlyingSocket)
      .withParameters(
        TLSParameters(serverNames = Some(List(new SNIHostName(address.host.toString))))
      )
      .build
      .use { tlsSocket =>
        tlsSocket.write(Chunk.empty) >>
          tlsSocket.session.map { session =>
            s"Cipher suite: ${session.getCipherSuite}\r\n" +
              "Peer certificate chain:\r\n" + session.getPeerCertificates.zipWithIndex
              .map { case (cert, idx) => s"Certificate $idx: $cert" }
              .mkString("\r\n")
          }
      }
  }
```

# Files

The `fs2.io.file` package provides support for working with files. The README example demonstrates the two simplest use cases -- incrementally reading from a file with `fs2.io.file.readAll` and incrementally writing to a file with `fs2.io.file.writeAll`. Another example is generating the SHA-256 digest of a file and writing it as hexadecimal in a sibling file:

```scala
import cats.effect.Concurrent
import fs2.{hash, text}
import fs2.io.file.{Files, Path}

def writeDigest[F[_]: Files: Concurrent](path: Path): F[Path] = {
  val target = Path(path.toString + ".sha256")
  Files[F].readAll(path)
    .through(hash.sha256)
    .through(text.hex.encode)
    .through(text.utf8.encode)
    .through(Files[F].writeAll(target))
    .compile
    .drain
    .as(target)
}
```

For more complex use cases, there are a few types available -- `FileHandle`, `ReadCursor`, and `WriteCursor`. A `FileHandle[F]` represents an open file and provides various methods for interacting with the file -- reading data, writing data, querying the size, etc. -- all in the effect `F`. Constructing a `FileHandle[F]` is accomplished by calling `FileHandle.fromPath(path, blocker)`, passing a `fs2.io.file.Path` value indicating which file to open.

The `ReadCursor` type pairs a `FileHandle[F]` with a byte offset in to the file. The methods on `ReadCursor` provide read operations that start at the current offset and return an updated cursor along with whatever data was read.

Similarly, `WriteCursor` pairs a `FileHandle[F]` with a byte offset. The methods on `WriteCursor` use the offset as the position to write the next chunk of bytes, returning an updated cursor.

The `fs2.io.file` package object also provides many ways to interact with the file system -- moving files, creating directories, walking all paths in a directory tree, watching directories for changes, etc. For example, tallying the total number of bytes in a directory tree can be accomplished with a single line of code:

```scala
def totalBytes[F[_]: Files: Concurrent](path: Path): F[Long] =
  Files[F].walk(path).evalMap(p => Files[F].size(p).handleError(_ => 0L)).compile.foldMonoid
```

As a slightly more complex example, we can count Scala lines of code by combining `walk`, `readAll`, and various parsing operations:

```scala
def scalaLineCount[F[_]: Files: Concurrent](path: Path): F[Long] =
  Files[F].walk(path).filter(_.extName == ".scala").flatMap { p =>
    Files[F].readAll(p).through(text.utf8.decode).through(text.lines).as(1L)
  }.compile.foldMonoid
```

Note that the `Files` object is file system agnostic. It is possible to use a custom one, i.e. mounted inside JAR file, to get access to the resources. Given there's already a `java.nio.file.FileSystem` created, interacting with the files can be made possible by calling `Path.fromFsPath` to get the `Path` object.

# Processes

The `fs2.io.process` package provides APIs for starting and interacting with native processes. Often you are interested in the output of a process.

```scala
import cats.effect.{Concurrent, MonadCancelThrow}
import fs2.io.process.{Processes, ProcessBuilder}
import fs2.text

def helloProcess[F[_]: Concurrent: Processes]: F[String] =
  ProcessBuilder("echo", "Hello, process!").spawn.use { process =>
    process.stdout.through(text.utf8.decode).compile.string
  }
```

You can also provide input.

```scala
def wordCountProcess[F[_]: Concurrent: Processes](words: String): F[Option[Int]] =
  ProcessBuilder("wc", "--words").spawn.use { process =>
    val in = Stream.emit(words).through(text.utf8.encode).through(process.stdin)
    val out = process.stdout.through(text.utf8.decode)
    out.concurrently(in).compile.string.map(_.strip.toIntOption)
  }
```

Or just wait for a process to complete and get the exit code.

```scala
def sleepProcess[F[_]: MonadCancelThrow: Processes]: F[Int] =
  ProcessBuilder("sleep", "1s").spawn.use(_.exitValue)
```

To terminate a running process, simply exit the `use` block.

```scala
import cats.effect.Temporal
import scala.concurrent.duration._

def oneSecondServer[F[_]: Temporal: Processes]: F[Unit] =
  ProcessBuilder("python", "-m", "http.server").spawn.surround {
    Temporal[F].sleep(1.second)
  }
```

# Console Operations

Writing to the console is often as simple as `s.evalMap(o => IO.println(o))`. This works fine for quick tests but can be problematic in large applications. The call to `println` blocks until data can be written to the output stream for standard out. This can cause fairness issues with other, non-blocking, operations running on the main thread pool. For such situations, `fs2-io` provides a couple of utilities:

```scala
def stdoutLines[F[_]: Sync, O: Show](
    charset: Charset = utf8Charset
): Pipe[F, O, INothing]

def stdout[F[_]: Sync]: Pipe[F, Byte, INothing]
```

Both of these pipes are provided in the `fs2.io` package object.  The `stdoutLines` method uses a `Show[O]` instance to convert the stream elements to strings.

The `fs2.io` package object also provides a couple of utilities for reading values from the console:

```scala
def stdin[F[_]: Sync](bufSize: Int): Stream[F, Byte]

def stdinUtf8[F[_]: Sync](bufSize: Int): Stream[F, String]
```

Like the output variants, these operations perform blocking reads on the blocking pool.

# Java Stream Interop

The `fs2.io` package object provides interop with `java.io.InputStream` and `java.io.OutputStream`.

The `fs2.io.readInputStream` method (and `unsafeReadInputStream` method, see ScalaDoc for differences) creates a `Stream[F, Byte]` from an `InputStream`.

The `fs2.io.writeOutputStream` method provides a pipe that writes the bytes emitted from a `Stream[F, Byte]` to an `OutputStream`.

The `fs2.io.readOutputStream` method creates a `Stream[F, Byte]` from a function which writes to an `OutputStream`.

[s2n-tls]: https://github.com/aws/s2n-tls
[`node:tls` module]: https://nodejs.org/api/tls.html