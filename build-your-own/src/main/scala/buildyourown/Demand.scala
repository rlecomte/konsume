package buildyourown

import Expr.*

/** A path from the root of the record: `.user.age` is `List("user", "age")`, the record itself
  * is `Nil`.
  */
type Path = List[String]

/** What a program needs from a record, found on its tree, before any record is read: the paths
  * whose values it uses (compares, or emits). The planned engine reads those, and nothing else.
  */
object Demand:

  /** The paths whose values `program` uses. */
  def of(program: Expr): Set[Path] =
    // the output: its value is used, unless it is the record itself (emitted with its own bytes)
    val output = pathOf(program, Some(Nil)).filter(_.nonEmpty)
    uses(program, Some(Nil)) ++ output

  /** The path of `p`'s output, given the path of its input, when it is known without running
    * the program: `.user.age` is a path, `select` outputs its input, a comparison computes a
    * boolean (no path).
    */
  def pathOf(p: Expr, in: Option[Path]): Option[Path] = p match
    case Identity       => in
    case Field(t, name) => pathOf(t, in).map(_ :+ name)
    case Select(_)      => in
    case Pipe(l, r)     => pathOf(r, pathOf(l, in))
    case _              => None

  /** The paths whose values `p` uses, given the path of its input. */
  private def uses(p: Expr, in: Option[Path]): Set[Path] = p match
    case Identity | Literal(_) => Set.empty
    case Field(t, _)           => uses(t, in) // `.user` in `.user.age`: only gone through
    case Compare(_, l, r)      => consumed(l, in) ++ consumed(r, in)
    case Select(cond)          => consumed(cond, in)
    case Pipe(l, r)            => uses(l, in) ++ uses(r, pathOf(l, in))

  /** `p` is an operand: what it uses, and its own value. */
  private def consumed(p: Expr, in: Option[Path]): Set[Path] = uses(p, in) ++ pathOf(p, in)
