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
package file

/**
 * Describes the permissions of a file / directory.
 *
 * Note: currently, there is only one subtype -- `PosixPermissions`.
 * More types may be added in the future (e.g. Windows ACLs).
 */
sealed trait Permissions extends PermissionsPlatform

import PosixPermission._

/**
 * Describes POSIX file permissions, where the user, group, and 
 * others are each assigned read, write, and/or execute permissions.
 * 
 * The `toString` method provides a 9 character string where the first
 * three characters indicate user permissions, the next three group permissions,
 * and the final three others permissions. For example, `rwxr-xr--` indicates
 * the owner has read, write and execute, the group as read and execute, and
 * others have read.
 * 
 * The `value` field encodes the permissions in the lowest 9 bits of an integer.
 * bits 8-6 indicate read, write, and execute for the owner, 5-3 for the group,
 * and 2-0 for others. `rwxr-xr--` has the integer value 111101100 = 492.
 * 
 * The `toOctalString` method returns the a 3 digit string, where the first
 * character indicates read, write and execute for the owner, the second digit
 * for the group, and the third digit for others. `rwxr-xr--` has the octal string
 * `754`.
 * 
 * Constructors from strings, octal strings, and integers, as well as explicitly
 * enumerating permissions, are provided in the companion.
 */
final class PosixPermissions private (val value: Int) extends Permissions {

  override def equals(that: Any): Boolean = that match {
    case other: PosixPermissions => value == other.value
    case _                       => false
  }

  override def hashCode: Int = value.hashCode

  override def toString: String = {
    val bldr = new StringBuilder
    bldr.append(if ((value & OwnerRead.value) != 0) "r" else "-")
    bldr.append(if ((value & OwnerWrite.value) != 0) "w" else "-")
    bldr.append(if ((value & OwnerExecute.value) != 0) "x" else "-")
    bldr.append(if ((value & GroupRead.value) != 0) "r" else "-")
    bldr.append(if ((value & GroupWrite.value) != 0) "w" else "-")
    bldr.append(if ((value & GroupExecute.value) != 0) "x" else "-")
    bldr.append(if ((value & OthersRead.value) != 0) "r" else "-")
    bldr.append(if ((value & OthersWrite.value) != 0) "w" else "-")
    bldr.append(if ((value & OthersExecute.value) != 0) "x" else "-")
    bldr.toString
  }

  def toOctalString: String = Integer.toString(value, 8)
}

object PosixPermissions {
  def apply(permissions: PosixPermission*): PosixPermissions =
    new PosixPermissions(permissions.foldLeft(0)(_ | _.value))

  private[file] def unapply(permissions: PosixPermissions): Some[Int] = Some(permissions.value)

  def fromInt(value: Int): Option[PosixPermissions] =
    // 511 = 0o777
    if (value < 0 || value > 511) None
    else Some(new PosixPermissions(value))

  def fromOctal(s: String): Option[PosixPermissions] =
    try {
      val value = Integer.parseInt(s, 8)
      fromInt(value)
    } catch {
      case _: NumberFormatException => None
    }

  private val Pattern = """(?:[r-][w-][x-]){3}""".r

  def fromString(s: String): Option[PosixPermissions] =
    s match {
      case Pattern() =>
        var value = 0
        if (s(0) == 'r') value = value | OwnerRead.value
        if (s(1) == 'w') value = value | OwnerWrite.value
        if (s(2) == 'x') value = value | OwnerExecute.value
        if (s(3) == 'r') value = value | GroupRead.value
        if (s(4) == 'w') value = value | GroupWrite.value
        if (s(5) == 'x') value = value | GroupExecute.value
        if (s(6) == 'r') value = value | OthersRead.value
        if (s(7) == 'w') value = value | OthersWrite.value
        if (s(8) == 'x') value = value | OthersExecute.value
        Some(new PosixPermissions(value))
      case _ => None
    }
}

sealed trait PosixPermission {
  val value: Int
}

object PosixPermission {
  case object OwnerRead extends PosixPermission { val value = 1 << 8 }
  case object OwnerWrite extends PosixPermission { val value = 1 << 7 }
  case object OwnerExecute extends PosixPermission { val value = 1 << 6 }
  case object GroupRead extends PosixPermission { val value = 1 << 5 }
  case object GroupWrite extends PosixPermission { val value = 1 << 4 }
  case object GroupExecute extends PosixPermission { val value = 1 << 3 }
  case object OthersRead extends PosixPermission { val value = 1 << 2 }
  case object OthersWrite extends PosixPermission { val value = 1 << 1 }
  case object OthersExecute extends PosixPermission { val value = 1 }
}
