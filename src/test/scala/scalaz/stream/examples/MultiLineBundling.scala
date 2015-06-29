package scalaz.stream

/**
 * Read in limericks in various ways - one using 5 lines at a time, one using "There was" to key the start of a new
 * limerick, and one using . to key the end of the old one.
 */

import scalaz.concurrent.Task

import org.scalacheck._
import Prop._
import java.io.FileInputStream

object MultiLineBundling extends Properties("multi-line-text-bundling") {

  case class Limerick(lines: Vector[String]) {
    lazy val tellLimerick = lines.mkString("\n")
    lazy val length = lines.length
  }

  property("5 at a time") = secure {
    // read the limericks file as a stream of strings containing the individual lines
    val src: Process[Task, String] = io.linesR("testdata/limericks.txt")

    // chunk by 5 lines at a time
    val fiveLines: Process[Task, Vector[String]] = src.chunk(5)

    // convert those 5 lines at a time to limericks - filter out empty vectors so no empty limerick is created
    val limericks: Process[Task, Limerick] = fiveLines.filter(_.nonEmpty).map { lines => Limerick(lines) }

    // force the collection to verify
    val check = limericks.runLog.run

    check.zip(LimerickChecks.limVec).forall {
      case (lim, compareStr) =>
        lim.length == 5 && lim.tellLimerick == compareStr
    } && check.size == 4
  }

  property("end on .") = secure {
    val src: Process[Task, String] = io.linesR("testdata/limericks.txt")

    // note that the predicate must return *false* on the termination condition,
    // i.e. it accumulates lines while the predicate is true, and cuts it off
    // when it turns false, e.g. when the line does not end with "." keep going
    val chunked: Process[Task, Vector[String]] = src.chunkBy(!_.endsWith("."))

    // convert chunks to limericks - note use of collect in this case to eliminate empty limericks
    val limericks: Process[Task, Limerick] = chunked.collect { case lines if lines.nonEmpty=> Limerick(lines) }

    // force the collection to verify
    val check = limericks.runLog.run

    check.zip(LimerickChecks.limVec).forall {
      case (lim, compareStr) =>
        lim.length == 5 && lim.tellLimerick == compareStr
    } && check.size == 4
  }

  property("starts with There was") = secure {
    val src: Process[Task, String] = io.linesR("testdata/limericks.txt")

    // note that the predicate must return *false* on the termination condition,
    // i.e. it accumulates lines while the predicate is true, and cuts it off
    // when it turns false, e.g. when the line does not end with "." keep going
    val chunked: Process[Task, Vector[String]] = src.chunkBy2 { case(s1, s2) => !s2.startsWith("There was")}

    // convert chunks to limericks - note use of collect in this case to eliminate empty limericks
    val limericks: Process[Task, Limerick] = chunked.collect { case lines if lines.nonEmpty=> Limerick(lines) }

    // force the collection to verify
    val check = limericks.runLog.run

    check.zip(LimerickChecks.limVec).forall {
      case (lim, compareStr) =>
        lim.length == 5 && lim.tellLimerick == compareStr
    } && check.size == 4
  }

  property("ends with ., starts with There was") = secure {
    val src: Process[Task, String] = io.linesR("testdata/limericks.txt")

    // note that the predicate must return *false* on the termination condition,
    // i.e. it accumulates lines while the predicate is true, and cuts it off
    // when it turns false, e.g. when the line does not end with "." keep going
    val chunked: Process[Task, Vector[String]] = src.chunkBy2 { case(s1, s2) => !(s1.endsWith(".") && s2.startsWith("There was"))}

    // convert chunks to limericks - note use of collect in this case to eliminate empty limericks
    val limericks: Process[Task, Limerick] = chunked.collect { case lines if lines.nonEmpty=> Limerick(lines) }

    // force the collection to verify
    val check = limericks.runLog.run

    check.zip(LimerickChecks.limVec).forall {
      case (lim, compareStr) =>
        lim.length == 5 && lim.tellLimerick == compareStr
    } && check.size == 4
  }

  property("chunk all into one mega limerick") = secure {
    val src: Process[Task, String] = io.linesR("testdata/limericks.txt")

    // note that the predicate must return *false* on the termination condition,
    // i.e. it accumulates lines while the predicate is true, and cuts it off
    // when it turns false, e.g. when the line does not end with "." keep going
    val chunked: Process[Task, Vector[String]] = src.chunkAll

    // convert chunks to limericks - note use of collect in this case to eliminate empty limericks
    val limericks: Process[Task, Limerick] = chunked.collect { case lines if lines.nonEmpty=> Limerick(lines) }

    // force the collection to verify
    val check = limericks.runLog.run

    // all 20 lines in one limerick
    check.zip(Vector(LimerickChecks.allLines)).forall {
      case (lim, compareStr) =>
        lim.length == 20 && lim.tellLimerick == compareStr
    } && check.size == 1
  }

}

object LimerickChecks {
  val lim1 =
    """There was a young lady of Cork,
      |Whose Pa made a fortune in pork;
      |He bought for his daughter
      |A tutor who taught her
      |To balance green peas on her fork.""".stripMargin
  val lim2 =
    """There was a young woman named Kite,
      |Whose speed was much faster than light,
      |She set out one day,
      |In a relative way,
      |And returned on the previous night.""".stripMargin
  val lim3 =
    """There was an old fellow named Green,
      |Who grew so abnormally lean,
      |And flat, and compressed,
      |That his back touched his chest,
      |And sideways he couldn't be seen.""".stripMargin
  val lim4 =
    """There was an old man in a hearse,
      |Who murmured, "This might have been worse;
      |Of course the expense
      |Is simply immense,
      |But it doesn't come out of my purse".""".stripMargin

  val limVec = Vector(lim1, lim2, lim3, lim4)

  val allLines = limVec.mkString("\n")
}