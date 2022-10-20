
import com.typesafe.tools.mima.core._

object MiMaFilters {
  val Library: Seq[ProblemFilter] = Seq(
    ProblemFilters.exclude[MissingClassProblem]("scala.annotation.internal.MappedAlternative"),
    ProblemFilters.exclude[MissingClassProblem]("scala.caps"),
    ProblemFilters.exclude[MissingClassProblem]("scala.caps$"),
    ProblemFilters.exclude[MissingClassProblem]("scala.annotation.retains"),
    ProblemFilters.exclude[MissingClassProblem]("scala.annotation.retainsUniversal"),
  )
}
