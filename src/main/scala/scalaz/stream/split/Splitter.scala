package scalaz.stream.split

import scalaz.Equal
import scalaz.syntax.equal._
import scalaz.std.vector._
import scalaz.stream.{ Process, Process1, process1 }
import Process.{ emit, emitAll, halt, receive1, receive1Or }

sealed trait Splitter[A] { self =>
  /**
   * A transducer that splits the stream according to this splitting strategy.
   */
  def split: Process1[A, Vector[A]] = withDelimiters.pipe(prepareDelimiters)

  /**
   * The underlying stream that includes delimiters in even-numbered positions.
   */
  private[split] def withDelimiters: Process1[A, Vector[A]]

  /**
   * A transducer that may manipulate delimiters in the underlying stream.
   */
  private[split] def prepareDelimiters: Process1[Vector[A], Vector[A]]

  /**
   * Collapse multiple consecutive delimiters into one.
   */
  def condense: Splitter[A] = new CondensingSplitter(this)

  /**
   * If the stream starts with a delimiter, don't include an initial blank.
   */
  def dropInitBlank: Splitter[A] = new WithPreparationSplitter(
    this,
    receive1((chunk: Vector[A]) =>
      if (chunk.isEmpty) halt else emit(chunk)
    ).fby(process1.id)
  )

  /**
   * If the stream ends with a delimiter, don't include a final blank.
   */
  def dropFinalBlank: Splitter[A] = new WithPreparationSplitter(
    this,
    process1.dropLastIf(_.isEmpty)
  )

  /**
   * Don't include any blanks in the output.
   */
  def dropBlanks: Splitter[A] = condense.dropFinalBlank.dropInitBlank

  /**
   * Don't include any delimiters in the output.
   */
  def dropDelims: Splitter[A] = new WithPreparationSplitter(
    this,
    process1.chunk(2).map(_.head)
  )

  /**
   * Append delimiters to preceding chunks.
   */
  def keepDelimsL: Splitter[A] = new WithPreparationSplitter(
    this,
    Process.await1[Vector[A]].fby(process1.chunk(2).map(_.flatten))
  )

  /**
   * Prepend delimiters to the chunks that follow them.
   */
  def keepDelimsR: Splitter[A] = new WithPreparationSplitter(
    this,
    process1.chunk(2).map(_.flatten)
  )
}

object Splitter {
  /**
   * The default splitting strategy: keep all delimiters and don't condense.
   */
  def default[A]: Splitter[A] = new DelimiterIncludingSplitter[A] {
    private[split] val withDelimiters = process1.chunkAll[A]
  }

  /**
   * Treat any element of the provided sequence as a delimiter.
   */
  def oneOf[A: Equal](delims: Vector[A]): Splitter[A] =
    whenElt[A](delims.contains)

  /**
   * Treat the provided sequence as a delimiter.
   */
  def onSubsequence[A: Equal](seq: Vector[A]): Splitter[A] =
    new DelimiterIncludingSplitter[A] {
      private[split] val withDelimiters = {
        def go(acc: Vector[A], matched: Int): Process1[A, Vector[A]] =
          receive1Or[A, Vector[A]](
            emit(acc ++ seq.take(matched))
          ) { a =>
            if (a === seq(matched)) {
              if (matched == seq.size - 1) {
                emitAll(Vector(acc, seq)).fby(go(Vector.empty, 0))
              } else {
                go(acc, matched + 1)
              }
            } else go(acc ++ seq.take(matched) :+ a, 0)
          }

        go(Vector.empty, 0)
      }
    }

  /**
   * Split on elements that satisfy the given predicate.
   */
  def whenElt[A](pred: A => Boolean): Splitter[A] =
    new DelimiterIncludingSplitter[A] {
      private[split] val withDelimiters = {
        def go(acc: Vector[A]): Process1[A, Vector[A]] =
          receive1Or[A, Vector[A]](emit(acc)) { a =>
            if (pred(a)) {
              emitAll(Vector(acc, Vector(a))).fby(go(Vector.empty))
            } else go(acc :+ a)
          }

        go(Vector.empty)
      }
  }

  def startsWith[A: Equal](seq: Vector[A]): Splitter[A] =
    onSubsequence(seq).keepDelimsL.dropInitBlank

  def startsWithOneOf[A: Equal](delims: Vector[A]): Splitter[A] =
    oneOf(delims).keepDelimsL.dropInitBlank

  def endsWith[A: Equal](seq: Vector[A]): Splitter[A] =
    onSubsequence(seq).keepDelimsR.dropFinalBlank

  def endsWithOneOf[A: Equal](delims: Vector[A]): Splitter[A] =
    oneOf(delims).keepDelimsR.dropFinalBlank
}

private[split] trait DelimiterIncludingSplitter[A] extends Splitter[A] {
  private[split] def prepareDelimiters = process1.id[Vector[A]]
}

private[split] class WithPreparationSplitter[A](
  underlying: Splitter[A],
  prepare: Process1[Vector[A], Vector[A]]
) extends Splitter[A] {
  private[split] val withDelimiters = underlying.withDelimiters
  private[split] val prepareDelimiters =
    underlying.prepareDelimiters.pipe(prepare)
}

private[split] class CondensingSplitter[A](underlying: Splitter[A])
  extends Splitter[A] {
  private[split] val withDelimiters = underlying.withDelimiters.pipe(
    go(WaitingForChunk(None))
  )
  private[split] val prepareDelimiters = underlying.prepareDelimiters

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
