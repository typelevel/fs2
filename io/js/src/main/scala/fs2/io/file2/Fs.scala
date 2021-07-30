package fs2.io.file2

import scala.scalajs.js

/** A facade for Node.js File system `fs`. Cast to/from your own bindings.
  * @see [[https://nodejs.org/api/fs.html]]
  */
@js.native
sealed trait Fs extends js.Object
