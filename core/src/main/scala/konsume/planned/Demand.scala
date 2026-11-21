package konsume.planned

import konsume.jq.Jq
import konsume.planned.Syntax.{iteratedOnce, pathOf, staticPath}

/** One step of a path: a field (`.user`), an element of an array (`[0]`), or each element of an
  * array in turn (`[]`, while the array is walked).
  */
enum Step:
  case Key(name: String)
  case At(index: Int)
  case Each

  override def toString: String = this match
    case Key(name) => s".$name"
    case At(index) => s"[$index]"
    case Each      => "[]"

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

  /** For a reader that walks no array below this value element by element (`.a[]`): each
    * array iterated is read whole instead, and iterated on its JSON view.
    */
  def withoutWalk: Demand =
    val walked = children.contains(Step.Each)
    Demand(asValue || walked, length, (children - Step.Each).map((s, d) => s -> d.withoutWalk))

object Demand:
  val empty = Demand(false, false, Map.empty)

  /** What a program does with a path: uses its value, takes its length, or only reaches it
    * (`.a[]`: the array is walked, even if nothing is used in its elements).
    */
  private enum Kind:
    case Value, Length, Reach

  private final case class Use(path: Path, kind: Kind)

  private def fromUses(uses: Iterable[Use]): Demand = uses.foldLeft(empty)((d, u) => add(d, u.path, u.kind))
  private def add(d: Demand, path: Path, kind: Kind): Demand = path match
    case Nil =>
      kind match
        case Kind.Value  => d.copy(asValue = true)
        case Kind.Length => d.copy(length = true)
        case Kind.Reach  => d
    case n :: rest => d.copy(children = d.children.updated(n, add(d.children.getOrElse(n, empty), rest, kind)))

  /** The paths `program` uses, from its syntax: the static paths whose value is consumed by an
    * operation (a comparison, a function, an emitted output), not only navigated through.
    * Emitting the record itself unchanged does not use it.
    */
  def of(program: Jq): Demand =
    val each   = iteratedOnce(program)
    val output = pathOf(program, Some(Nil), each).filter(_.nonEmpty).map(value).toList
    fromUses(Uses(each)(program, Some(Nil)) ++ output)

  private def value(p: Path) = Use(p, Kind.Value)

  /** The uses of `p`, the arrays in `each` being walked element by element. */
  private final class Uses(each: Path => Boolean):
    private def consume(p: Jq, in: Option[Path]): List[Use] = apply(p, in) ++ pathOf(p, in, each).map(value)

    def apply(p: Jq, in: Option[Path]): List[Use] = p match
      case Jq.Identity | Jq.Literal(_) | Jq.Empty => Nil
      case Jq.Length                              => in.map(Use(_, Kind.Length)).toList
      case Jq.Not                                 => in.map(value).toList
      case Jq.Field(t, _)                         => if staticPath(p, in).isDefined then Nil else consume(t, in)
      case Jq.Index(t, _)                         => if staticPath(p, in).isDefined then Nil else consume(t, in)
      case Jq.Iterate(t) =>
        pathOf(t, in, each).filter(each) match
          case Some(array) => apply(t, in) :+ Use(array :+ Step.Each, Kind.Reach) // walked, not read
          case None        => consume(t, in)
      case Jq.Pipe(l, r)          => apply(l, in) ++ apply(r, pathOf(l, in, each))
      case Jq.Compare(_, l, r)    => consume(l, in) ++ consume(r, in)
      case Jq.And(l, r)           => consume(l, in) ++ consume(r, in)
      case Jq.Or(l, r)            => consume(l, in) ++ consume(r, in)
      case Jq.Select(c)           => consume(c, in)
      case Jq.Test(Jq.Literal(_)) => in.map(value).toList
      case Jq.Test(x)             => in.map(value).toList ++ consume(x, in)
      case Jq.StartsWith(x)       => in.map(value).toList ++ consume(x, in)
      case Jq.EndsWith(x)         => in.map(value).toList ++ consume(x, in)
