---
layout: page
title:  "I/O"
section: "io"
position: 3
---

The `fs2-io` library provides support for performing input and output on the JVM (not Scala.js). This includes:
- [Networking](#networking)
  - [TCP](#tcp)
  - [UDP](#udp)
  - [TLS](#tls)
- [Files](#files)
- [Console operations](#console-operations)
- [Interop with `java.io.{InputStream, OutputStream}`](#java-stream-interop)

In this section, we'll look at each of these features.

# Networking

The `fs2-io` library supports both TCP, via the `fs2.io.tcp` package, and UDP, via the `fs2.io.udp` package. Both packages provide purely functional abstractions on top of the Java NIO networking support. Furthermore, both packages take full advantage of the resource safety guarantees of cats-effect and `fs2.Stream`.

## TCP

The `fs2.io.tcp.Socket` trait provides mechanisms for reading and writing data -- both as individual actions and as part of stream programs.

### Clients

To get started, let's write a client program that connects to a server, sends a message, and reads a response.

```scala mdoc
import fs2.{Chunk, Stream}
import fs2.io.tcp.{Socket, SocketGroup}
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.implicits._
import java.net.InetSocketAddress

def client[F[_]: Concurrent: ContextShift]: F[Unit] =
  Blocker[F].use { blocker =>
    SocketGroup[F](blocker).use { socketGroup =>
      socketGroup.client(new InetSocketAddress("localhost", 5555)).use { socket =>
        socket.write(Chunk.bytes("Hello, world!".getBytes)) >>
          socket.read(8192).flatMap { response =>
            Sync[F].delay(println(s"Response: $response"))
          }
      }
    }
  }
```

To open a socket that's connected to `localhost:5555`, we need to use the `client` method on `SocketGroup`. Besides providing ways to create sockets, a `SocketGroup` provides a runtime environment for processing network events for the sockets it creates. Hence, the `apply` method of `SocketGroup` returns a `Resource[F, SocketGroup]`, which ensures the runtime environment is shutdown safely after usage of the group and all sockets created from it has finished. To create a `SocketGroup`, we needed a `Blocker`, which is also created inline here. Normally, a single `Blocker` and a single `SocketGroup` is created at startup and used for the lifetime of the application. To stress this, we can rewrite our TCP client example in this way:

```scala mdoc
import cats.effect.{ExitCode, IO, IOApp}

object ClientApp extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        client[IO](socketGroup)
      }
    }.as(ExitCode.Success)

  def client[F[_]: Concurrent: ContextShift](socketGroup: SocketGroup): F[Unit] =
    socketGroup.client(new InetSocketAddress("localhost", 5555)).use { socket =>
      socket.write(Chunk.bytes("Hello, world!".getBytes)) >>
        socket.read(8192).flatMap { response =>
          Sync[F].delay(println(s"Response: $response"))
        }
    }
}
```

The `socketGroup.client` method returns a `Resource[F, Socket[F]]` which automatically closes the socket after the resource has been used. To write data to the socket, we call `socket.write`, which takes a `Chunk[Byte]` and returns an `F[Unit]`. Once the write completes, we do a single read from the socket via `socket.read`, passing the maximum amount of bytes we want to read. The returns an `F[Option[Chunk[Byte]]]` -- `None` if the socket reaches end of input and `Some` if the read produced a chunk. Finally, we print the response to the console.

Note we aren't doing any binary message framing or packetization in this example. Hence, it's very possible for the single read to only receive a portion of the original message -- perhaps just the bytes for `"Hello, w"`. We can use FS2 streams to simplify this. The `Socket` trait defines `Stream` operations  -- `writes` and `reads`. We could rewrite this example using the stream operations like so:

```scala mdoc
import fs2.text

def client[F[_]: Concurrent: ContextShift](socketGroup: SocketGroup): Stream[F, Unit] =
  Stream.resource(socketGroup.client(new InetSocketAddress("localhost", 5555))).flatMap { socket =>
    Stream("Hello, world!")
      .through(text.utf8Encode)
      .through(socket.writes())
      .drain ++
        socket.reads(8192)
          .through(text.utf8Decode)
          .evalMap { response =>
            Sync[F].delay(println(s"Response: $response"))
          }
  }
```

The structure changes a bit. First, the socket resource is immediately lifted in to a stream via `Stream.resource`. Second, we create a single `Stream[Pure, String]`, transform it with `text.utf8Encode` to turn it in to a `Stream[Pure, Byte]`, and then transform it again with `socket.writes()` which turns it in to a `Stream[F, Unit]`. The `socket.writes` method returns a pipe that writes each underlying chunk of the input stream to the socket. The resulting stream is drained since we don't use the unit values, giving us a `Stream[F, Nothing]`.

We then append a stream that reads a respnose -- we do this via `socket.reads(8192)`, which gives us a `Stream[F, Byte]` that terminates when the socket is closed or it receives an end of input indication. We transform that stream with `text.utf8Decode`, which gives us a `Stream[F, String]`. We then print each received response to the console.

This program won't end until the server side closes the socket or indicates there's no more data to be read. To fix this, we need a protocol that both the client and server agree on. Since we are working with text, let's use a simple protocol where each frame (or "packet" or "message") is terminated with a `\n`. We'll have to update both the write side and the read side of our client.

```scala mdoc:nest
def client[F[_]: Concurrent: ContextShift](socketGroup: SocketGroup): Stream[F, Unit] =
  Stream.resource(socketGroup.client(new InetSocketAddress("localhost", 5555))).flatMap { socket =>
    Stream("Hello, world!")
      .interleave(Stream.constant("\n"))
      .through(text.utf8Encode)
      .through(socket.writes())
      .drain ++
        socket.reads(8192)
          .through(text.utf8Decode)
          .through(text.lines)
          .head
          .evalMap { response =>
            Sync[F].delay(println(s"Response: $response"))
          }
  }
```

To update the write side, we added `.interleave(Stream.constant("\n"))` before doing UTF8 encoding. This results in every input string being followed by a `"\n"`. On the read side, we transformed the output of `utf8Decode` with `text.lines`, which emits the strings between newlines. Finally, we call `head` to take the first full line of output. Note that we discard the rest of the `reads` stream after processing the first full line. This results in the socket getting closed and cleaned up correctly.

### Servers

Now let's implement a server application that communicates with the client app we just built. The server app will be a simple echo server -- for each line of text it receives, it will reply with the same line.

```scala mdoc
def echoServer[F[_]: Concurrent: ContextShift](socketGroup: SocketGroup): F[Unit] =
  socketGroup.server(new InetSocketAddress(5555)).map { clientResource =>
    Stream.resource(clientResource).flatMap { client =>
      client.reads(8192)
        .through(text.utf8Decode)
        .through(text.lines)
        .interleave(Stream.constant("\n"))
        .through(text.utf8Encode)
        .through(client.writes())
    }
  }.parJoin(100).compile.drain
```

We start with a call to `socketGroup.server` which returns a value of an interesting type -- `Stream[F, Resource[F, Socket[F]]]`. This is an infinite stream of client connections -- each time a client connects to the server, a `Resource[F, Socket[F]]` is emitted, allowing interaction with that client. The client socket is provided as a resource, allowing us to use the resource and then release it, closing the underlying socket and returning any acquired resources to the runtime environment.

We map over this infinite stream of clients and provide the logic for handling an individual client. In this case,
we read from the client socket, UTF-8 decode the received bytes, extract individual lines, and then write each line back to the client. This logic is implemented as a single `Stream[F, Unit]`.

Since we mapped over the infinite client stream, we end up with a `Stream[F, Stream[F, Unit]]`. We flatten this to a single `Stream[F, Unit]` via `parJoin(100)`, which runs up to 100 of the inner streams concurrently. As inner streams finish, new inner streams are pulled from the source. Hence, `parJoin` is controlling the maximum number of concurrent client requests our server processes.

The pattern of `socketGroup.server(address).map(handleClient).parJoin(maxConcurrentClients)` is very common when working with server sockets.

A simpler echo server could be implemented with this core logic:

```scala
client.reads(8192).through(client.writes())
```

However, such an implementation would echo bytes back to the client as they are received instead of only echoing back full lines of text.

## UDP

UDP support works much the same way as TCP. The `fs2.io.udp.Socket` trait provides mechanisms for reading and writing UDP datagrams. UDP sockets are created via the `open` method on `fs2.io.udp.SocketGroup`. Unlike TCP, there's no differentiation between client and server sockets. Additionally, since UDP is a packet based protocol, read and write operations use `fs2.io.udp.Packet` values, which consist of a `Chunk[Byte]` and an `InetSocketAddress`. A packet is equivalent to a UDP datagram.

Adapting the TCP client example for UDP gives us the following:

```scala mdoc:reset
import fs2.{Chunk, text, Stream}
import fs2.io.udp.{Packet, Socket, SocketGroup}
import cats.effect.{Concurrent, ContextShift, Sync}
import cats.implicits._
import java.net.InetSocketAddress

def client[F[_]: Concurrent: ContextShift](socketGroup: SocketGroup): F[Unit] = {
  val address = new InetSocketAddress("localhost", 5555)
  Stream.resource(socketGroup.open()).flatMap { socket =>
    Stream("Hello, world!")
      .through(text.utf8Encode)
      .chunks
      .map(data => Packet(address, data))
      .through(socket.writes())
      .drain ++
        socket.reads()
          .flatMap(packet => Stream.chunk(packet.bytes))
          .through(text.utf8Decode)
          .evalMap { response =>
            Sync[F].delay(println(s"Response: $response"))
          }
  }.compile.drain
}
```

When writing, we map each chunk of bytes to a `Packet`, which includes the destination address of the packet. When reading, we convert the `Stream[F, Packet]` to a `Stream[F, Byte]` via `flatMap(packet => Stream.chunk(packet.bytes))`. Otherwise, the example is unchanged.

```scala mdoc
def echoServer[F[_]: Concurrent: ContextShift](socketGroup: SocketGroup): F[Unit] =
  Stream.resource(socketGroup.open(new InetSocketAddress(5555))).flatMap { socket =>
    socket.reads().through(socket.writes())
  }.compile.drain
```

The UDP server implementation is much different than the TCP server implementation. We open a socket that's bound to port `5555`. We then read packets from that socket, writing each packet back out. Since each received packet has the remote address of the sender, we can reuse the same packet for writing.

## TLS

The `fs2.io.tls` package provides support for TLS over TCP and DTLS over UDP, built on top of `javax.net.ssl`. TLS over TCP is provided by the `TLSSocket` trait, which is instantiated by methods on `TLSContext`. A `TLSContext` provides cryptographic material used in TLS session establishment -- e.g., the set of certificates that are trusted (sometimes referred to as a trust store) and optionally, the set of certificates identifying this application (sometimes referred to as a key store). The `TLSContext` companion provides many ways to construct a `TLSContext` -- for example:
- `TLSContext.system(blocker)` - delegates to `javax.net.ssl.SSLContext.getDefault`, which uses the JDK default set of trusted certificates
- `TLSContext.fromKeyStoreFile(pathToFile, storePassword, keyPassword, blocker)` - loads a Java Key Store file
- `TLSContext.insecure(blocker)` - trusts all certificates - note: this is dangerously insecure - only use for quick tests

A `TLSContext` is typically created at application startup and used for all sockets for the lifetime of the application. Once a `TLSContext` has been created, the `client` and `server` methods are used to create `TLSSocket` instances (and `dtlsClient` / `dtlsServer` methods for `DTLSSocket`). In each case, a regular socket must be provided, which the `TLSSocket` will use for performing the TLS handshake as well as transmitting and receiving encrypted data. `TLSSocket` extends `fs2.io.tcp.Socket`, making the addition of TLS support a drop in replacement for a program using `fs2-io`.

Adapting the TCP echo client for TLS looks like this:

```scala mdoc:reset
import fs2.{Chunk, Stream, text}
import fs2.io.tcp.SocketGroup
import fs2.io.tls.TLSContext
import cats.effect.{Concurrent, ContextShift, Sync}
import cats.implicits._
import java.net.InetSocketAddress

def client[F[_]: Concurrent: ContextShift](
  socketGroup: SocketGroup,
  tlsContext: TLSContext): Stream[F, Unit] = {
  val address = new InetSocketAddress("localhost", 5555)
  Stream.resource(socketGroup.client(address)).flatMap { underlyingSocket =>
    Stream.resource(tlsContext.client(underlyingSocket)).flatMap { socket =>
      Stream("Hello, world!")
        .interleave(Stream.constant("\n"))
        .through(text.utf8Encode)
        .through(socket.writes())
        .drain ++
          socket.reads(8192)
            .through(text.utf8Decode)
            .through(text.lines)
            .head
            .evalMap { response =>
              Sync[F].delay(println(s"Response: $response"))
            }
    }
  }
}
```

The only difference is that we wrap the underlying socket with a `TLSSocket`.

### Configuring TLS Session Parameters

The various methods on `TLSContext` that create `TLSSocket`s and `DTLSSocket`s all take a `TLSParameters` argument, allowing session level configuration. This allows configuration of things like client authentication, supported protocols and cipher suites, and SNI extensions. For example:

```scala mdoc
import fs2.io.tls.{TLSParameters, TLSSocket}
import cats.effect.Resource
import javax.net.ssl.SNIHostName

def tlsClientWithSni[F[_]: Concurrent: ContextShift](
  socketGroup: SocketGroup,
  tlsContext: TLSContext,
  address: InetSocketAddress): Resource[F, TLSSocket[F]] =
  socketGroup.client[F](address).flatMap { underlyingSocket =>
    tlsContext.client(
      underlyingSocket,
      TLSParameters(
        protocols = Some(List("TLSv1.3")),
        serverNames = Some(List(new SNIHostName(address.getHostName)))
      )
    )
  }
```

In this example, we've configured the TLS session to require TLS 1.3 and we've added an SNI extension with the hostname of the target server.

### Accessing TLS Session Information

`TLSSocket` extends `Socket` and provides a few additional methods. One particularly interesting method is `session`, which returns a `F[javax.net.ssl.SSLSession]`, containing information about the established TLS session. This allows us to query for things like the peer certificate or the cipher suite that was negotiated.

In the following example, we extract various information about the session, in order to help debug TLS connections.

```scala mdoc
def debug[F[_]: Concurrent: ContextShift](
    socketGroup: SocketGroup,
    tlsContext: TLSContext,
    address: InetSocketAddress
): F[String] =
  socketGroup.client[F](address).use { underlyingSocket =>
    tlsContext
      .client(
        underlyingSocket,
        TLSParameters(serverNames = Some(List(new SNIHostName(address.getHostName))))
      )
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

The `fs2.io.file` package provides support for working with files. The README example demonstrates the two simplest use cases -- incrementally reading from a file with `fs2.io.file.readAll` and incrementally writing to a file with `fs2.io.file.writeAll`.

For more complex use cases, there are a few types available -- `FileHandle`, `ReadCursor`, and `WriteCursor`. A `FileHandle[F]` represents an open file and provides various methods for interacting with the file -- reading data, writing data, querying the size, etc. -- all in the effect `F`. Constructing a `FileHandle[F]` is accomplished by calling `FileHandle.fromPath(path, blocker)`, passing a `java.nio.file.Path` value indicating which file to open.

The `ReadCursor` type pairs a `FileHandle[F]` with a byte offset in to the file. The methods on `ReadCursor` provide read operations that start at the current offset and return an updated cursor along with whatever data was read.

Similarly, `WriteCursor` pairs a `FileHandle[F]` with a byte offset. The methods on `WriteCursor` use the offset as the position to write the next chunk of bytes, returning an updated cursor.

The `fs2.io.file` package object also provides many ways to interact with the file system -- moving files, creating directories, walking all paths in a diretory tree, watching directories for changes, etc.

# Console Operations

Writing to the console is often as simple as `s.evalMap(o => IO(println(o)))`. This works fine for quick tests but can be problematic in large applications. The call to `println` blocks until data can be written to the output stream for standard out. This can cause fairness issues with other, non-blocking, operations running on the main thread pool. For such situations, `fs2-io` provides a couple of utilities:

```scala
def stdoutLines[F[_]: Sync: ContextShift, O: Show](
    blocker: Blocker,
    charset: Charset = utf8Charset
): Pipe[F, O, Unit]

def stdout[F[_]: Sync: ContextShift](blocker: Blocker): Pipe[F, Byte, Unit]
```

Both of these pipes are provided in the `fs2.io` package object. Both wrap calls to the console with a `blocker.delay` to avoid fairness issues. The `stdoutLines` method uses a `Show[O]` instance to convert the stream elements to strings. Note these pipes may be more expensive than simplying doing a blocking println, depending on the application. We're trading fairness for the overhead of shifting execution to a blocking thread pool.

The `fs2.io` package object also provides a couple of utilities for reading values from the console:

```scala
def stdin[F[_]: Sync: ContextShift](bufSize: Int, blocker: Blocker): Stream[F, Byte]

def stdinUtf8[F[_]: Sync: ContextShift](bufSize: Int, blocker: Blocker): Stream[F, String]
```

Like the output variants, these operations perform blocking reads on a blocking pool.

# Java Stream Interop

The `fs2.io` package object provides interop with `java.io.InputStream` and `java.io.OutputStream`.

The `fs2.io.readInputStream` method (and `unsafeReadInputStream` method, see ScalaDoc for differences) creates a `Stream[F, Byte]` from an `InputStream`.

The `fs2.io.writeOutputStream` method provides a pipe that writes the bytes emitted from a `Stream[F, Byte]` to an `OutputStream`.

The `fs2.io.readOutputStream` method creates a `Stream[F, Byte]` from a function which writes to an `OutputStream`.
