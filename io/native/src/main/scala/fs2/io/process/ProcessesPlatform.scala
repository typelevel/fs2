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
import fs2.{Stream, Pipe}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.*
import scala.scalanative.posix.sys.wait.*
import scala.scalanative.posix.errno.*
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.unistd.*
import scala.scalanative.posix.spawn.*
import scala.scalanative.posix.signal.*
import java.io.IOException
import scala.concurrent.duration.*
import cats.effect.LiftIO
import cats.effect.IO
import org.typelevel.scalaccompat.annotation._

@extern
@nowarn212("cat=unused")
object SyscallBindings {
  def syscall(number: CLong, arg1: CLong, arg2: CLong): CLong = extern
}

object PidFd {
  private val SYS_pidfd_open = 434L
  val PIDFD_NONBLOCK = 1

  def pidfd_open(pid: pid_t, flags: Int): Int = {
    val fd = SyscallBindings.syscall(SYS_pidfd_open, pid.toLong, flags.toLong)
    fd.toInt
  }
}

private[process] trait ProcessesCompanionPlatform {
  def forAsync[F[_]: LiftIO](implicit F: Async[F]): Processes[F] = new UnsealedProcesses[F] {

    def spawn(process: ProcessBuilder): Resource[F, Process[F]] = {

      def createProcess(): F[NativeProcess] = F.blocking {
        Zone { implicit z =>
          val allArgs = process.command +: process.args
          val argv = stackalloc[CString](allArgs.length.toULong + 1.toULong)
          allArgs.zipWithIndex.foreach { case (arg, i) =>
            argv(i.toULong) = toCString(arg)
          }
          argv(allArgs.length.toULong) = null

          val envMap =
            if (process.inheritEnv)
              sys.env ++ process.extraEnv
            else
              process.extraEnv

          val envp = stackalloc[CString]((envMap.size + 1).toULong)
          envMap.zipWithIndex.foreach { case ((k, v), i) =>
            envp(i.toULong) = toCString(s"$k=$v")
          }
          envp(envMap.size.toULong) = null

          val stdinPipe = stackalloc[CInt](2)
          val stdoutPipe = stackalloc[CInt](2)
          val stderrPipe = stackalloc[CInt](2)

          if (pipe(stdinPipe) != 0 || pipe(stdoutPipe) != 0 || pipe(stderrPipe) != 0) {
            throw new RuntimeException("Failed to create pipes")
          }

          val fileActions = stackalloc[posix_spawn_file_actions_t]()
          posix_spawn_file_actions_init(fileActions)

          posix_spawn_file_actions_adddup2(fileActions, stdinPipe(0), STDIN_FILENO)
          posix_spawn_file_actions_addclose(fileActions, stdinPipe(1))

          posix_spawn_file_actions_adddup2(fileActions, stdoutPipe(1), STDOUT_FILENO)
          posix_spawn_file_actions_addclose(fileActions, stdoutPipe(0))

          posix_spawn_file_actions_adddup2(fileActions, stderrPipe(1), STDERR_FILENO)
          posix_spawn_file_actions_addclose(fileActions, stderrPipe(0))

          val pid = stackalloc[pid_t]()
          val result = posix_spawnp(
            pid,
            argv(0),
            fileActions,
            null,
            argv,
            envp
          )

          posix_spawn_file_actions_destroy(fileActions)

          if (result != 0) {
            close(stdinPipe(0)); close(stdinPipe(1))
            close(stdoutPipe(0)); close(stdoutPipe(1))
            close(stderrPipe(0)); close(stderrPipe(1))
            throw new RuntimeException(s"posix_spawn failed: $result")
          }

          close(stdinPipe(0))
          close(stdoutPipe(1))
          close(stderrPipe(1))

          NativeProcess(
            pid = !pid,
            stdinFd = stdinPipe(1),
            stdoutFd = stdoutPipe(0),
            stderrFd = stderrPipe(0)
          )
        }
      }

      def cleanup(proc: NativeProcess): F[Unit] =
        F.blocking {
          close(proc.stdinFd); close(proc.stdoutFd); close(proc.stderrFd)
        } *>
          F.delay(kill(proc.pid, SIGKILL)) *>
          F.blocking {
            val status = stackalloc[CInt]()
            val r = waitpid(proc.pid, status, WNOHANG)
            if (r < 0 && errno.errno != ECHILD)
              F.raiseError(new RuntimeException(s"waitpid failed: errno=${errno.errno}"))
            ()
          }

      Resource.make(createProcess())(cleanup).map { nativeProcess =>
        new UnsealedProcess[F] {
          def isAlive: F[Boolean] = F.delay {
            kill(nativeProcess.pid, 0) == 0 || errno.errno != ESRCH
          }

          def exitValue: F[Int] =
            if (LinktimeInfo.isLinux) {
              F.delay(PidFd.pidfd_open(nativeProcess.pid, PidFd.PIDFD_NONBLOCK)).flatMap { pidfd =>
                if (pidfd >= 0) {
                  fileDescriptorPoller[F].flatMap { poller =>
                    poller
                      .registerFileDescriptor(pidfd, true, false)
                      .use { handle =>
                        handle.pollReadRec(()) { _ =>
                          IO {
                            Zone { _ =>
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
                      }
                      .to
                  }
                } else {
                  fallbackExitValue(nativeProcess.pid).to
                }
              }
            } else {
              fallbackExitValue(nativeProcess.pid).to
            }

          def stdin: Pipe[F, Byte, Nothing] = writeFd(nativeProcess.stdinFd)

          def stdout: Stream[F, Byte] = readFd(nativeProcess.stdoutFd, 8192)

          def stderr: Stream[F, Byte] = readFd(nativeProcess.stderrFd, 8192)
        }
      }
    }

    private def fallbackExitValue(pid: pid_t): IO[Int] = {
      def loop: IO[Int] =
        IO.blocking {
          Zone { _ =>
            val status = stackalloc[CInt]()
            val result = waitpid(pid, status, WNOHANG)
            if (result == pid) Some(WEXITSTATUS(!status))
            else if (result == 0) None
            else throw new IOException(s"waitpid failed with errno: ${errno.errno}")
          }
        }.flatMap {
          case Some(code) => IO.pure(code)
          case None       => IO.sleep(100.millis) >> loop
        }

      loop.onCancel {
        IO.blocking {
          kill(pid, SIGKILL)
          ()
        }
      }
    }

  }
}

case class NativeProcess(
    pid: pid_t,
    stdinFd: Int,
    stdoutFd: Int,
    stderrFd: Int
)
