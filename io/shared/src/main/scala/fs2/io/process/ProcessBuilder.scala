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

  /** Configures how stdout and stderr should be handled. */
  def outputMode: ProcessOutputMode

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

  /** @see [[outputMode]] */
  def withOutputMode(outputMode: ProcessOutputMode): ProcessBuilder

  /** Starts the process and returns a handle for interacting with it.
    * Closing the resource will kill the process if it has not already terminated.
    */
  final def spawn[F[_]: Processes]: Resource[F, Process[F]] =
    Processes[F].spawn(this)
}

sealed trait ProcessOutputMode
object ProcessOutputMode {
  case object Separate extends ProcessOutputMode // stdout and stderr are separate
  case object Merged extends ProcessOutputMode   // stderr is redirected to stdout
  case class FileOutput(path: Path) extends ProcessOutputMode // Output to file
  case object Inherit extends ProcessOutputMode  // Inherit parent process's streams
  case object Ignore extends ProcessOutputMode   // Discard output
}

object ProcessBuilder {

  def apply(command: String, args: List[String]): ProcessBuilder =
    ProcessBuilderImpl(command, args, true, Map.empty, None, ProcessOutputMode.Separate)

  def apply(command: String, args: String*): ProcessBuilder =
    apply(command, args.toList)

  private final case class ProcessBuilderImpl(
      command: String,
      args: List[String],
      inheritEnv: Boolean,
      extraEnv: Map[String, String],
      workingDirectory: Option[Path],
      outputMode: ProcessOutputMode
  ) extends ProcessBuilder {

    def withCommand(command: String): ProcessBuilder = copy(command = command)

    def withArgs(args: List[String]): ProcessBuilder = copy(args = args)

    def withInheritEnv(inherit: Boolean): ProcessBuilder = copy(inheritEnv = inherit)

    def withExtraEnv(env: Map[String, String]): ProcessBuilder = copy(extraEnv = env)

    def withWorkingDirectory(workingDirectory: Path): ProcessBuilder =
      copy(workingDirectory = Some(workingDirectory))

    def withCurrentWorkingDirectory: ProcessBuilder = copy(workingDirectory = None)

    def withOutputMode(outputMode: ProcessOutputMode): ProcessBuilder = copy(outputMode = outputMode)
  }
}
