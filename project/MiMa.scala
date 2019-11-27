case class LibraryVersion(major: Int, minor: Int, patch: Option[Int], suffix: Option[String]) {
  override def toString =
    s"$major.$minor" +
      patch.fold("")("." + _) +
      suffix.fold("")("-" + _)
}

object MiMa {
  // Matches versions like "0.8", "0.8.1", "0.8.1-SNAPSHOT", "0.8.1-3-53de4b5d"
  def extractVersion(version: String): Option[LibraryVersion] = {
    val VersionExtractor = """(\d+)\.(\d+)(?:\.(\d+))?(?:-(.*))?""".r
    version match {
      case VersionExtractor(major, minor, patch, suffix) =>
        Some(LibraryVersion(major.toInt, minor.toInt, Option(patch).map(_.toInt), Option(suffix)))
      case _ => None
    }
  }

  // RC and SNAPSHOT suffixes represent pre-release builds,
  // cannot check compatibility against unreleased software.
  def suffixAfterRelease(version: LibraryVersion): Boolean =
    version.suffix match {
      case Some(s) if s.startsWith("RC") => false
      case Some(s) if s == "SNAPSHOT"    => false
      case None                          => false
      case _                             => true
    }

  // Return version of library to check for compatibility
  def targetLibraryVersion(currentVersion: LibraryVersion): Option[LibraryVersion] = {
    // Default target version has patch 0, no suffix.
    // e.g. "0.8.1-SNAPSHOT" is compared with "0.8.0"
    val targetVersion = currentVersion.copy(patch = Some(0), suffix = None)

    val shouldCheck: Boolean =
      (currentVersion.patch, targetVersion.patch, suffixAfterRelease(currentVersion)) match {
        case (Some(current), Some(target), _) if current > target       => true
        case (Some(current), Some(target), suffix) if current == target => suffix
        case (Some(current), None, _)                                   => true
        case _                                                          => false
      }

    if (shouldCheck)
      Some(targetVersion)
    else
      None
  }

  def targetVersion(currentVersion: String): Option[String] =
    for {
      version <- extractVersion(currentVersion)
      target <- targetLibraryVersion(version)
    } yield target.toString
}
