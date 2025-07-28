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
import scala.scalanative.posix.errno.*
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.unistd.*
import scala.scalanative.posix.signal.*
import java.io.IOException
import cats.effect.LiftIO
import cats.effect.IO
import org.typelevel.scalaccompat.annotation._
import scala.concurrent.duration.*
import cats.effect.implicits.*

@extern
@nowarn212("cat=unused")
object SyscallBindings {
  def syscall(number: CLong, arg1: CLong, arg2: CLong): CLong = extern
}

object pidFd {
  private val SYS_pidfd_open = 434L

  def pidfd_open(pid: pid_t, flags: Int): Int = {
    val fd = SyscallBindings.syscall(SYS_pidfd_open, pid.toLong, flags.toLong)
    fd.toInt
  }
}

final case class NativeProcess(
    pid: pid_t,
    stdinFd: Int,
    stdoutFd: Int,
    stderrFd: Int
)

private[process] trait ProcessesCompanionPlatform extends Processesjvmnative {

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
    fds.foreach { fd => close(fd); () }

  def forAsync[F[_]: LiftIO](implicit F: Async[F]): Processes[F] =
    if (LinktimeInfo.isMac || LinktimeInfo.isLinux) {
      new UnsealedProcesses[F] {
        def spawn(process: ProcessBuilder): Resource[F, Process[F]] = {

          def createProcess(): F[NativeProcess] = F.blocking {
            Zone { implicit z =>
              val stdinPipe = stackalloc[CInt](2.toUInt)
              val stdoutPipe = stackalloc[CInt](2.toUInt)
              val stderrPipe = stackalloc[CInt](2.toUInt)

              if (pipe(stdinPipe) != 0 || pipe(stdoutPipe) != 0 || pipe(stderrPipe) != 0) {
                throw new RuntimeException("Failed to create pipes")
              }
              val envMap =
                if (process.inheritEnv)
                  sys.env ++ process.extraEnv
                else process.extraEnv

              val envp = stackalloc[CString]((envMap.size + 1).toULong)
              envMap.zipWithIndex.foreach { case ((k, v), i) =>
                envp(i.toULong) = toCString(s"$k=$v")
              }
              envp(envMap.size.toULong) = null

              val allArgs = process.command +: process.args
              val argv = stackalloc[CString](allArgs.length.toULong + 1.toULong)
              allArgs.zipWithIndex.foreach { case (arg, i) =>
                argv(i.toULong) = toCString(arg)
              }
              argv(allArgs.length.toULong) = null

              val executable = findExecutable(process.command).getOrElse(process.command)

              fork() match {
                case -1 =>
                  closeAll(
                    stdinPipe(0),
                    stdinPipe(1),
                    stdoutPipe(0),
                    stdoutPipe(1),
                    stderrPipe(0),
                    stderrPipe(1)
                  )
                  throw new IOException("Unable to fork process")
                case 0 =>
                  closeAll(stdinPipe(1), stdoutPipe(0), stderrPipe(0))
                  if (
                    dup2(stdinPipe(0), STDIN_FILENO) == -1 ||
                    dup2(stdoutPipe(1), STDOUT_FILENO) == -1 ||
                    dup2(stderrPipe(1), STDERR_FILENO) == -1
                  ) {
                    _exit(1)
                    throw new IOException("Unable to redirect file descriptors")
                  }
                  closeAll(stdinPipe(0), stdoutPipe(1), stderrPipe(1))

                  process.workingDirectory.foreach { dir =>
                    if ((dir != null) && (dir.toString != ".")) {
                      val ret = chdir(toCString(dir.toString))
                      if (ret != 0)
                        throw new IOException(s"Failed to chdir to ${dir.toString}")
                    }
                  }

                  execve(toCString(executable), argv, envp)
                  _exit(127)
                  throw new IOException(s"Failed to create process for command: ${process.command}")
                case pid =>
                  closeAll(stdinPipe(0), stdoutPipe(1), stderrPipe(1))
                  NativeProcess(
                    pid = pid,
                    stdinFd = stdinPipe(1),
                    stdoutFd = stdoutPipe(0),
                    stderrFd = stderrPipe(0)
                  )
              }
            }
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

          Resource.make(createProcess())(cleanup).map { nativeProcess =>
            new UnsealedProcess[F] {
              def isAlive: F[Boolean] = F.delay {
                kill(nativeProcess.pid, 0) == 0 || errno.errno != EPERM
              }

              def exitValue: F[Int] =
                if (LinktimeInfo.isLinux) {
                  F.delay(pidFd.pidfd_open(nativeProcess.pid, 0)).flatMap { pidfd =>
                    if (pidfd >= 0) {
                      fileDescriptorPoller[F].flatMap { poller =>
                        poller
                          .registerFileDescriptor(pidfd, true, false)
                          .use { handle =>
                            handle.pollReadRec(()) { _ =>
                              IO {
                                val statusPtr = stackalloc[CInt]()
                                val result = waitpid(nativeProcess.pid, statusPtr, WNOHANG)

                                if (result == nativeProcess.pid) {
                                  val exitCode = WEXITSTATUS(!statusPtr)
                                  Right(exitCode)
                                } else if (result == 0) {
                                  Left(())
                                } else {
                                  if (errno.errno == ECHILD) {
                                    throw new IOException("No such process")
                                  } else {
                                    throw new IOException(
                                      s"waitpid failed with errno: ${errno.errno}"
                                    )
                                  }
                                }
                              }
                            }
                          }
                          .to
                      }
                    } else {
                      fallbackExitValue(nativeProcess.pid)
                    }
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

        private def fallbackExitValue(pid: pid_t): F[Int] = {
          def loop: F[Int] =
            F.blocking {
              Zone { _ =>
                val status = stackalloc[CInt]()
                val result = waitpid(pid, status, WNOHANG)

                if (result == pid) {
                  Some(WEXITSTATUS(!status))
                } else if (result == 0) None
                else throw new IOException(s"waitpid failed with errno: ${errno.errno}")
              }
            }.flatMap {
              case Some(code) => F.pure(code)
              case None       => F.sleep(10.millis) >> loop
            }

          loop.onCancel {
            F.blocking {
              kill(pid, SIGKILL)
              ()
            }
          }
        }
      }
    } else super.forAsync[F]
}
