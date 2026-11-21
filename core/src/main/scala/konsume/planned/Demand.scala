package konsume.planned

import konsume.jq.Jq
import konsume.planned.Syntax.{pathOf, staticPath}

/** One step of a path: a field (`.user`) or an element of an array (`[0]`). */
enum Step:
  case Key(name: String)
  case At(index: Int)

  override def toString: String = this match
    case Key(name) => s".$name"
    case At(index) => s"[$index]"

/** A path from the root of the record: `.items[0]` is `List(Key("items"), At(0))`. */
type Path = List[Step]

def show(path: Path): String = if path.isEmpty then "." else path.mkString

/** The paths a program uses, as a tree: `asValue` when it uses the value itself, `length` when
  * it only takes its length (an array can then be counted instead of read).
  */
final case class Demand(asValue: Boolean, length: Boolean, children: Map[Step, Demand]):
  /** The paths whose value, or only length, is used: each one gets a [[PVal]]. */
  def valuePaths(prefix: Path): List[Path] =
    (if asValue || length then List(prefix) else Nil) ++ children.toList.flatMap((n, d) => d.valuePaths(prefix :+ n))

  /** Only the length of the value is used, not the value nor its fields. */
  def lengthOnly: Boolean = length && !asValue && children.isEmpty

object Demand:
  val empty = Demand(false, false, Map.empty)

  /** A use of a path: its value, or only its length. */
  private final case class Use(path: Path, lengthOnly: Boolean)

  private def fromUses(uses: Iterable[Use]): Demand = uses.foldLeft(empty)((d, u) => add(d, u.path, u.lengthOnly))
  private def add(d: Demand, path: Path, lengthOnly: Boolean): Demand = path match
    case Nil       => if lengthOnly then d.copy(length = true) else d.copy(asValue = true)
    case n :: rest => d.copy(children = d.children.updated(n, add(d.children.getOrElse(n, empty), rest, lengthOnly)))

  /** The paths `program` uses, from its syntax: the static paths whose value is consumed by an
    * operation (a comparison, a function, an emitted output), not only navigated through.
    * Emitting the record itself unchanged does not use it.
    */
  def of(program: Jq): Demand =
    val output = pathOf(program, Some(Nil)).filter(_.nonEmpty).map(value).toList
    fromUses(uses(program, Some(Nil)) ++ output)

  private def value(p: Path) = Use(p, lengthOnly = false)

  private def consume(p: Jq, in: Option[Path]): List[Use] = uses(p, in) ++ pathOf(p, in).map(value)

  private def uses(p: Jq, in: Option[Path]): List[Use] = p match
    case Jq.Identity | Jq.Literal(_) | Jq.Empty => Nil
    case Jq.Length                              => in.map(Use(_, lengthOnly = true)).toList
    case Jq.Not                                 => in.map(value).toList
    case Jq.Field(t, _)                         => if staticPath(p, in).isDefined then Nil else consume(t, in)
    case Jq.Index(t, _)                         => if staticPath(p, in).isDefined then Nil else consume(t, in)
    case Jq.Iterate(t)                          => consume(t, in)
    case Jq.Pipe(l, r)                          => uses(l, in) ++ uses(r, pathOf(l, in))
    case Jq.Compare(_, l, r)                    => consume(l, in) ++ consume(r, in)
    case Jq.And(l, r)                           => consume(l, in) ++ consume(r, in)
    case Jq.Or(l, r)                            => consume(l, in) ++ consume(r, in)
    case Jq.Select(c)                           => consume(c, in)
    case Jq.Test(Jq.Literal(_))                 => in.map(value).toList
    case Jq.Test(x)                             => in.map(value).toList ++ consume(x, in)
    case Jq.StartsWith(x)                       => in.map(value).toList ++ consume(x, in)
    case Jq.EndsWith(x)                         => in.map(value).toList ++ consume(x, in)
