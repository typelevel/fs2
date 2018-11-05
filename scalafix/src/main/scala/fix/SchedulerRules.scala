package fix
import scalafix.v1._
import scala.meta._

object SchedulerRules {

  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] =
    t.collect {
      case t @ scheduler(_: Type.Name) =>
        Patch.replaceTree(t, timer(Type.Name("F")).toString()) //TODO: Use actual effect
      case Term.ApplyType(scheduler(_), List(f)) =>
        Patch.replaceTree(t, timer(f).toString())
      case sched @ Term.Apply(
            Term.ApplyType(Term.Select(Term.Select(s, Term.Name("effect")), Term.Name("sleep")), _),
            List(d)) => //TODO: use symbol matcher to make sure we are changing fs2 scheduler
        val timerSleep = Term.Apply(Term.Select(s, Term.Name("sleep")), List(d))
        Patch.replaceTree(sched, timerSleep.toString())
      case sched @ Term
            .Apply(Term.ApplyType(Term.Select(s, Term.Name("sleep")), List(f)), List(d)) =>
        val stream =
          Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("sleep")), List(f)),
                     List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
            .Apply(Term.ApplyType(Term.Select(s, Term.Name("sleep_")), List(f)), List(d)) =>
        val stream =
          Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("sleep_")), List(f)),
                     List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
            .Apply(Term.ApplyType(Term.Select(s, Term.Name("awakeEvery")), List(f)), List(d)) =>
        val stream = Term.Apply(
          Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("awakeEvery")), List(f)),
          List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
            .Apply(Term.Select(s, Term.Name("retry")), params) =>
        val stream = Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("retry")), params)
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term.Apply(
            Term.Select(
              Term.Apply(
                Term.Select(Term.Name("Stream"), Term.Name("eval")),
                List(
                  Term.Select(
                    Term.ApplyType(Term.Name("Effect"), List(Type.Name("F"))),
                    Term.Name("unit")
                  )
                )
              ),
              Term.Name("through")
            ),
            List(
              Term.Apply(
                Term.Select(Term.Name("scheduler"), Term.Name("debounce")),
                List(Term.Name("duration"))
              )
            )
          ) =>
        Patch.empty //TODO
    }

  def stream(f: Type, a: Term): Term =
    f match {
      case Type.Name("Pure") =>
        Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("emit")), List(a))
      case _ =>
        Term.Apply(
          Term.Select(Term.Name("Stream"), Term.Name("eval")),
          List(
            Term.Apply(
              Term.Select(Term.ApplyType(Term.Name("Applicative"), List(f)), Term.Name("pure")),
              List(a)))
        )
    }

  def timer(f: Type) = Type.Apply(Type.Name("Timer"), List(f))

  val scheduler = SymbolMatcher.normalized("fs2/Scheduler")

}
