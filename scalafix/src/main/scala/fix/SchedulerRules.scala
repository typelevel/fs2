package fix
import scalafix.v1._
import scala.meta._

object SchedulerRules {

  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] =
    t.collect {
      case t @ schedulerMatcher(_: Type.Name) =>
        Patch.replaceTree(t, timer(Type.Name("F")).toString()) //TODO: Use actual effect
      case Term.ApplyType(s, List(f)) if isScheduler(s) =>
        Patch.replaceTree(t, timer(f).toString())
      case sched @ Term.Apply(
            Term.ApplyType(Term.Select(Term.Select(s, Term.Name("effect")), Term.Name("sleep")), _),
            List(d)) if isScheduler(s) =>
        val timerSleep = Term.Apply(Term.Select(s, Term.Name("sleep")), List(d))
        Patch.replaceTree(sched, timerSleep.toString())
      case sched @ Term
            .Apply(Term.ApplyType(Term.Select(s, Term.Name("sleep")), List(f)), List(d))
          if isScheduler(s) =>
        val stream =
          Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("sleep")), List(f)),
                     List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
            .Apply(Term.ApplyType(Term.Select(s, Term.Name("sleep_")), List(f)), List(d))
          if isScheduler(s) =>
        val stream =
          Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("sleep_")), List(f)),
                     List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
            .Apply(Term.ApplyType(Term.Select(s, Term.Name("awakeEvery")), List(f)), List(d))
          if isScheduler(s) =>
        val stream = Term.Apply(
          Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("awakeEvery")), List(f)),
          List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
            .Apply(Term.Select(s, Term.Name("retry")), params) if isScheduler(s) =>
        val stream = Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("retry")), params)
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term.Apply(
            Term.Select(
              s,
              Term.Name("through")
            ),
            List(
              Term.Apply(
                Term.Select(_, debounce @ Term.Name("debounce")),
                d
              )
            )
          ) if isStream(s) =>
        val newStream = Term.Apply(Term.Select(s, debounce), d)
        Patch.replaceTree(sched, newStream.toString())
      case sched @ Term.Apply(
            Term.Select(Term.Select(s, Term.Name("effect")), Term.Name("delayCancellable")),
            List(fa, d)) if isScheduler(s) =>
        val concurrent = Term.Apply(
          Term.Select(
            Term.ApplyType(Term.Name("Concurrent"), List(Type.Name("F"))), //TODO: Use actual effect
            Term.Name("race")),
          List(fa, Term.Apply(Term.Select(s, Term.Name("sleep")), List(d)))
        )
        Patch.replaceTree(sched, concurrent.toString())
      case sched @ Term.Apply(Term.Select(s, Term.Name("delay")), List(stream, d))
          if isScheduler(s) && isStream(stream) =>
        val newStream = Term.Apply(Term.Select(stream, Term.Name("delayBy")), List(d))
        Patch.replaceTree(sched, newStream.toString())
    }

  def timer(f: Type) = Type.Apply(Type.Name("Timer"), List(f))

  def isScheduler(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(schedulerMatcher.matches)

  def isStream(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(streamMatcher.matches)

  val schedulerMatcher = SymbolMatcher.normalized("fs2/Scheduler")
  val streamMatcher = SymbolMatcher.normalized("fs2/Stream")

}
