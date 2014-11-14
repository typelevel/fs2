package scalaz.stream.split

import scalaz.Equal
import scalaz.syntax.equal._
import scalaz.std.vector._
import scalaz.stream.Process1
import scalaz.stream.Process.{ await1, emit, emitAll, halt, receive1, receive1Or }
import scalaz.stream.process1.{ chunk, chunkAll, dropLastIf, feed1, id, lift }

/**
 * A splitting strategy that defines how to split a stream and what to do with
 * delimiters. While the API is inspired by Haskell's `Data.List.Split`, the
 * implementation differs significantly.
 */
trait Splitter[A] { self =>
  /**
   * A transducer that splits the stream according to this splitting strategy.
   */
  final def split: Process1[A, Vector[A]] =
    prepareDelims.fold(withDelims)(withDelims.pipe)

  /**
   * The underlying stream, which includes delimiters in odd-numbered positions
   * (assuming zero-based indexing).
   */
  def withDelims: Process1[A, Vector[A]]

  /**
   * An optional transducer that may manipulate delimiters in the underlying
   * stream.
   */
  def prepareDelims: Option[Process1[Vector[A], Vector[A]]] = None

  /**
   * Collapse multiple consecutive delimiters into one.
   */
  def condense: Splitter[A] = new CondensingSplitter(this)

  /**
   * Add the given `Process1` to the transducer used to process delimiters.
   */
  def addDelimPipe(pipe: Process1[Vector[A], Vector[A]]) =
    new Splitter[A] {
      val withDelims = self.withDelims
      override val prepareDelims = Some(
        self.prepareDelims.fold(pipe)(_.pipe(pipe))
      )
    }

  /**
   * If the stream starts with a delimiter, don't include an initial blank.
   */
  def dropInitBlank: Splitter[A] = addDelimPipe(
    receive1((chunk: Vector[A]) =>
      if (chunk.isEmpty) halt else emit(chunk)
    ).fby(id)
  )

  /**
   * If the stream ends with a delimiter, don't include a final blank.
   */
  def dropFinalBlank: Splitter[A] = addDelimPipe(dropLastIf(_.isEmpty))

  /**
   * Don't include any blanks in the output.
   */
  def dropBlanks: Splitter[A] = condense.dropFinalBlank.dropInitBlank

  /**
   * Don't include any delimiters in the output.
   */
  def dropDelims: Splitter[A] = addDelimPipe(chunk(2).map(_.head))

  /**
   * Prepend delimiters to the chunks that follow them.
   */
  def keepDelimsL: Splitter[A] = addDelimPipe(
    await1[Vector[A]].fby(chunk(2).map(_.flatten))
  )

  /**
   * Append delimiters to preceding chunks.
   */
  def keepDelimsR: Splitter[A] = addDelimPipe(chunk(2).map(_.flatten))
}

object Splitter {
  def apply[A](underlying: Process1[A, Vector[A]]) = new Splitter[A] {
    val withDelims = underlying
  }

  /**
   * The default splitting strategy: keep all delimiters and don't condense.
   */
  def default[A]: Splitter[A] = new Splitter[A] {
    val withDelims = chunkAll[A]
  }

  /**
   * Treat any element of the provided sequence as a delimiter.
   */
  def oneOf[A: Equal](delims: Vector[A]): Splitter[A] =
    whenElt[A](a => delims.exists(a === _))

  /**
   * Treat the provided sequence as a delimiter. We follow `Data.List.Split` in
   * breaking before every element in the case of an empty input sequence.
   */
  def onSubsequence[A: Equal](seq: Vector[A]): Splitter[A] = if (seq.isEmpty) {
    apply(
      emit(Vector.empty[A]).fby(
        lift(Vector(_: A))
      ).intersperse(Vector.empty[A])
    )
  } else new KmpSearchSplitter(seq)

  /**
   * Split on elements that satisfy the given predicate.
   */
  def whenElt[A](pred: A => Boolean): Splitter[A] = apply[A] {
    def go(acc: Vector[A]): Process1[A, Vector[A]] =
      receive1Or[A, Vector[A]](emit(acc)) { a =>
        if (pred(a)) {
          emitAll(Vector(acc, Vector(a))).fby(go(Vector.empty))
        } else go(acc :+ a)
      }

    go(Vector.empty)
  }

  /**
   * Make a strategy that splits a list into chunks that all start with the
   * given subsequence (except possibly the first).
   */
  def startsWith[A: Equal](seq: Vector[A]): Splitter[A] =
    onSubsequence(seq).keepDelimsL.dropInitBlank

  /**
   * Make a strategy that splits a list into chunks that all start with one of
   * the given elements (except possibly the first).
   */
  def startsWithOneOf[A: Equal](delims: Vector[A]): Splitter[A] =
    oneOf(delims).keepDelimsL.dropInitBlank

  /**
   * Make a strategy that splits a list into chunks that all end with the given
   * subsequence, except possibly the last.
   */
  def endsWith[A: Equal](seq: Vector[A]): Splitter[A] =
    onSubsequence(seq).keepDelimsR.dropFinalBlank

  /**
   * Make a strategy that splits a list into chunks that all end with one of the
   * given elements, except possibly the last.
   */
  def endsWithOneOf[A: Equal](delims: Vector[A]): Splitter[A] =
    oneOf(delims).keepDelimsR.dropFinalBlank
}

/**
 * A splitting strategy that collapses multiple consecutive delimiters into one.
 */
private[split] class CondensingSplitter[A](wrapped: Splitter[A])
  extends Splitter[A] {
  /**
   * Use the wrapped strategy's transducer for delimiters.
   */
  override val prepareDelims = wrapped.prepareDelims

  val withDelims = wrapped.withDelims.pipe(
    go(WaitingForChunk(None))
  )

  sealed trait State {
    def cleanup: Process1[Vector[A], Vector[A]]
    def next(as: Vector[A]): (Process1[Vector[A], Vector[A]], State)
  }

  case class WaitingForChunk(acc: Option[Vector[A]]) extends State {
    val cleanup =
      acc.fold[Process1[Vector[A], Vector[A]]](halt)(emit)

    def next(as: Vector[A]) = if (as.nonEmpty || acc.isEmpty) {
      (emitAll(acc.toVector :+ as), WaitingForDelim(None))
    } else {
      (halt, WaitingForDelim(acc))
    }
  }

  case class WaitingForDelim(acc: Option[Vector[A]]) extends State {
    val cleanup = acc.fold[Process1[Vector[A], Vector[A]]](halt)(as =>
      emitAll(Vector(as, Vector.empty))
    )

    def next(as: Vector[A]) =
      (halt, WaitingForChunk(Some(acc.fold(as)(_ ++ as))))
  }

  def go(state: State): Process1[Vector[A], Vector[A]] =
    receive1Or[Vector[A], Vector[A]](state.cleanup) { as =>
      val (proc, nextState) = state.next(as)

      proc.fby(go(nextState))
    }
}

/**
 * A splitter that uses the Knuth–Morris–Pratt algorithm to find (and break on)
 * exact matches of a given sequence in the stream.
 */
private[split] class KmpSearchSplitter[A: Equal](seq: Vector[A])
  extends Splitter[A] {
  private[this] val table: Vector[Int] = {
    val arr = Array.ofDim[Int](seq.size)
    arr(0) = -1

    var pos = 2
    var cnd = 0

    while (pos < seq.size) {
      if (seq(pos - 1) === seq(cnd)) {
        cnd += 1
        arr(pos) = cnd
        pos += 1
      } else if (cnd > 0) {
        cnd = arr(cnd)
      } else {
        pos += 1
      }
    }

    arr.toVector
  }

  val withDelims = {
    def go(acc: Vector[A], matched: Int): Process1[A, Vector[A]] =
      receive1Or[A, Vector[A]](emit(acc ++ seq.take(matched))) { a =>
        if (a === seq(matched)) {
          if (matched == seq.size - 1) {
            emitAll(Vector(acc, seq)).fby(go(Vector.empty, 0))
          } else {
            go(acc, matched + 1)
          }
        } else {
          val t = table(matched)

          if (t > -1) {
            feed1(a)(go(acc ++ seq.take(matched - t), t))
          } else {
            go(acc ++ seq.take(matched) :+ a, 0)
          }
        }
      }

    go(Vector.empty, 0)
  }
}
