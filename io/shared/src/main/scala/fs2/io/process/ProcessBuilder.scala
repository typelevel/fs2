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

package fs2.io
package process

import cats.effect.kernel.Resource
import fs2.io.file.Path

sealed abstract class ProcessBuilder private {

  /** Command to run. */
  def command: String

  /** Arguments passed to command. */
  def args: List[String]

  /** Whether to inherit environment variables of the current process.
    * Defaults to `true`.
    */
  def inheritEnv: Boolean

  /** Additional environment variables for this process.
    * These may override inherited environment variables.
    */
  def extraEnv: Map[String, String]

  /** Working directory for this process.
    * If `None` then it will use the working directory of the current process.
    * Defaults to `None`
    */
  def workingDirectory: Option[Path]

  /** @see [[command]] */
  def withCommand(command: String): ProcessBuilder

  /** @see [[args]] */
  def withArgs(args: List[String]): ProcessBuilder

  /** @see [[inheritEnv]] */
  def withInheritEnv(inherit: Boolean): ProcessBuilder

  /** @see [[extraEnv]] */
  def withExtraEnv(env: Map[String, String]): ProcessBuilder

  /** @see [[workingDirectory]] */
  def withWorkingDirectory(workingDirectory: Path): ProcessBuilder

  /** @see [[workingDirectory]] */
  def withCurrentWorkingDirectory: ProcessBuilder

  /** Starts the process and returns a handle for interacting with it.
    * Closing the resource will kill the process if it has not already terminated.
    */
  final def spawn[F[_]: ProcessSpawn]: Resource[F, Process[F]] =
    ProcessSpawn[F].spawn(this)
}

object ProcessBuilder {

  def apply(command: String, args: List[String]): ProcessBuilder =
    ProcessBuilderImpl(command, args, true, Map.empty, None)

  def apply(command: String, args: String*): ProcessBuilder =
    apply(command, args.toList)

  private final case class ProcessBuilderImpl(
      command: String,
      args: List[String],
      inheritEnv: Boolean,
      extraEnv: Map[String, String],
      workingDirectory: Option[Path]
  ) extends ProcessBuilder {

    def withCommand(command: String): ProcessBuilder = copy(command = command)

    def withArgs(args: List[String]): ProcessBuilder = copy(args = args)

    def withInheritEnv(inherit: Boolean): ProcessBuilder = copy(inheritEnv = inherit)

    def withExtraEnv(env: Map[String, String]): ProcessBuilder = copy(extraEnv = env)

    def withWorkingDirectory(workingDirectory: Path): ProcessBuilder =
      copy(workingDirectory = Some(workingDirectory))
    def withCurrentWorkingDirectory: ProcessBuilder = copy(workingDirectory = None)
  }

}
