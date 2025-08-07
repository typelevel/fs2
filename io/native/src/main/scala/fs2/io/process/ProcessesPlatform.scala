/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package process

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.*
import scala.scalanative.posix.sys.wait.*
import scala.scalanative.posix.errno.{EPERM, ECHILD}
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.unistd.*
import scala.scalanative.posix.signal.*
import java.io.IOException
import cats.effect.LiftIO
import cats.effect.IO
import org.typelevel.scalaccompat.annotation._
import fs2.io.internal.NativeUtil._

@extern
@nowarn212("cat=unused")
object LibC {
  def pidfd_open(pid: CInt, flags: CInt): CInt = extern
}

private final case class NativeProcess(
    pid: pid_t,
    stdinFd: Int,
    stdoutFd: Int,
    stderrFd: Int,
    pidfd: Option[Int] = None
)

private[process] trait ProcessesCompanionPlatform extends ProcessesCompanionJvmNative {

  private def findExecutable(cmd: String)(implicit z: Zone): Option[String] = {
    val pathEnv = sys.env.getOrElse("PATH", "")
    pathEnv
      .split(':')
      .find { dir =>
        val full = s"$dir/$cmd"
        access(toCString(full), X_OK) == 0
      }
      .map(dir => s"$dir/$cmd")
  }

  @inline private def closeAll(fds: Int*): Unit =
    fds.foreach(close)

  def pipeResource[F[_]](implicit F: Async[F]): Resource[F, (Int, Int)] =
    Resource.make {
      F.blocking {
        val fds = stackalloc[CInt](2.toUInt)
        guard_(pipe(fds))
        (fds(0), fds(1))
      }
    } { case (r, w) => F.blocking { close(r); close(w); () } }

  def forAsync[F[_]: LiftIO](implicit F: Async[F]): Processes[F] =
    if (LinktimeInfo.isMac || LinktimeInfo.isLinux) {
      new UnsealedProcesses[F] {
        def spawn(process: ProcessBuilder): Resource[F, Process[F]] = {

          val pipesResource: Resource[F, ((Int, Int), (Int, Int), (Int, Int))] =
            for {
              stdinPipe <- pipeResource[F]
              stdoutPipe <- pipeResource[F]
              stderrPipe <- pipeResource[F]
            } yield (stdinPipe, stdoutPipe, stderrPipe)

          def createProcess(
              stdinPipe: (Int, Int),
              stdoutPipe: (Int, Int),
              stderrPipe: (Int, Int)
          ): F[NativeProcess] = F.blocking {
            val nativeProcess: NativeProcess = Zone { implicit z =>
              val envMap =
                if (process.inheritEnv)
                  sys.env ++ process.extraEnv
                else process.extraEnv

              val envp = stackalloc[CString]((envMap.size + 1).toUSize)
              envMap.zipWithIndex.foreach { case ((k, v), i) =>
                envp(i.toUSize) = toCString(s"$k=$v")
              }
              envp(envMap.size.toUSize) = null

              val allArgs = process.command +: process.args
              val argv = stackalloc[CString](allArgs.length.toUSize + 1.toUSize)
              allArgs.zipWithIndex.foreach { case (arg, i) =>
                argv(i.toUSize) = toCString(arg)
              }
              argv(allArgs.length.toUSize) = null

              val executable =
                if (process.command.startsWith("/"))
                  process.command
                else
                  findExecutable(process.command).getOrElse(process.command)
              val ret = guard(fork())
              ret match {
                case 0 =>
                  closeAll(stdinPipe._2, stdoutPipe._1, stderrPipe._1)
                  guard_(dup2(stdinPipe._1, STDIN_FILENO))
                  guard_(dup2(stdoutPipe._2, STDOUT_FILENO))
                  guard_(dup2(stderrPipe._2, STDERR_FILENO))
                  closeAll(stdinPipe._1, stdoutPipe._2, stderrPipe._2)

                  process.workingDirectory.foreach { dir =>
                    if ((dir != null) && (dir.toString != ".")) {
                      guard_(chdir(toCString(dir.toString)))
                    }
                  }

                  execve(toCString(executable), argv, envp)
                  _exit(127)
                  throw new AssertionError("unreachable")
                case pid =>
                  closeAll(stdinPipe._1, stdoutPipe._2, stderrPipe._2)
                  val pidfd =
                    if (LinktimeInfo.isLinux) {
                      val fd = LibC.pidfd_open(pid, 0)
                      if (fd >= 0) Some(fd) else None
                    } else None
                  NativeProcess(
                    pid = pid,
                    stdinFd = stdinPipe._2,
                    stdoutFd = stdoutPipe._1,
                    stderrFd = stderrPipe._1,
                    pidfd
                  )
              }
            }: NativeProcess
            nativeProcess
          }

          def cleanup(proc: NativeProcess): F[Unit] =
            F.blocking {
              closeAll(proc.stdinFd, proc.stdoutFd, proc.stderrFd)
              val alive = {
                val res = kill(proc.pid, 0)
                res == 0 || errno.errno == EPERM
              }
              if (alive) {
                kill(proc.pid, SIGKILL)
                val status = stackalloc[CInt]()
                waitpid(proc.pid, status, 0)
                ()
              } else {
                val status = stackalloc[CInt]()
                waitpid(proc.pid, status, WNOHANG)
                ()
              }
            }

          pipesResource.flatMap { case (stdinPipe, stdoutPipe, stderrPipe) =>
            Resource
              .make(createProcess(stdinPipe, stdoutPipe, stderrPipe))(cleanup)
              .flatMap { nativeProcess =>
                nativeProcess.pidfd match {
                  case Some(pidfd) =>
                    for {
                      poller <- Resource.eval(fileDescriptorPoller[F])
                      handle <- poller.registerFileDescriptor(pidfd, true, false).mapK(LiftIO.liftK)
                    } yield (nativeProcess, Some(handle))
                  case None =>
                    Resource.pure((nativeProcess, None))
                }
              }
              .map { case (nativeProcess, pollHandleOpt) =>
                new UnsealedProcess[F] {
                  def isAlive: F[Boolean] = F.delay {
                    kill(nativeProcess.pid, 0) == 0 || errno.errno == EPERM
                  }

                  def exitValue: F[Int] =
                    if (LinktimeInfo.isLinux) {
                      (nativeProcess.pidfd, pollHandleOpt) match {
                        case (Some(_), Some(handle)) =>
                          handle
                            .pollReadRec(()) { _ =>
                              IO {
                                val statusPtr = stackalloc[CInt]()
                                val result = waitpid(nativeProcess.pid, statusPtr, WNOHANG)
                                if (result == nativeProcess.pid) {
                                  val exitCode = WEXITSTATUS(!statusPtr)
                                  Right(exitCode)
                                } else if (result == 0) {
                                  Left(())
                                } else {
                                  if (errno.errno == ECHILD)
                                    throw new IOException("No such process")
                                  else
                                    throw new IOException(
                                      s"waitpid failed with errno: ${errno.errno}"
                                    )
                                }
                              }
                            }
                            .to
                        case _ =>
                          fallbackExitValue(nativeProcess.pid)
                      }
                    } else {
                      fallbackExitValue(nativeProcess.pid)
                    }

                  def stdin: Pipe[F, Byte, Nothing] = { in =>
                    in
                      .through(writeFd(nativeProcess.stdinFd))
                      .onFinalize {
                        F.blocking {
                          close(nativeProcess.stdinFd)
                        }.void
                      }
                  }

                  def stdout: Stream[F, Byte] = readFd(nativeProcess.stdoutFd, 8192)
                    .onFinalize {
                      F.blocking {
                        close(nativeProcess.stdoutFd)
                      }.void
                    }

                  def stderr: Stream[F, Byte] = readFd(nativeProcess.stderrFd, 8192)
                    .onFinalize {
                      F.blocking {
                        close(nativeProcess.stderrFd)
                      }.void
                    }
                }
              }
          }
        }

        private def fallbackExitValue(pid: pid_t): F[Int] = F.blocking {
          val status = stackalloc[CInt]()
          guard_(waitpid(pid, status, 0))
          WEXITSTATUS(!status)
        }
      }
    } else super.forAsync[F]
}
