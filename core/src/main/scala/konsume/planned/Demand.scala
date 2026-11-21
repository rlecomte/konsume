package konsume.planned

import konsume.jq.Jq
import konsume.planned.Syntax.{pathOf, staticPath}

/** A path from the root of the record: `.user.age` is `List("user", "age")`. */
type Path = List[String]

/** The paths a program uses, as a tree: `asValue` when it uses the value itself, not only
  * some of its fields.
  */
final case class Demand(asValue: Boolean, children: Map[String, Demand]):
  def valuePaths(prefix: Path): List[Path] =
    (if asValue then List(prefix) else Nil) ++ children.toList.flatMap((n, d) => d.valuePaths(prefix :+ n))

object Demand:
  val empty = Demand(false, Map.empty)
  private def fromPaths(paths: Iterable[Path]): Demand = paths.foldLeft(empty)(add)
  private def add(d: Demand, path: Path): Demand = path match
    case Nil       => d.copy(asValue = true)
    case n :: rest => d.copy(children = d.children.updated(n, add(d.children.getOrElse(n, empty), rest)))

  /** The paths `program` uses, from its syntax: the static paths whose value is consumed by an
    * operation (a comparison, a function, an emitted output), not only navigated through.
    * Emitting the record itself unchanged does not use it.
    */
  def of(program: Jq): Demand =
    val output = pathOf(program, Some(Nil)).filter(_.nonEmpty).toList
    fromPaths(uses(program, Some(Nil)) ++ output)

  private def consume(p: Jq, in: Option[Path]): List[Path] = uses(p, in) ++ pathOf(p, in).toList

  private def uses(p: Jq, in: Option[Path]): List[Path] = p match
    case Jq.Identity | Jq.Literal(_) | Jq.Empty => Nil
    case Jq.Not | Jq.Length                     => in.toList
    case Jq.Field(t, _)                         => if staticPath(p, in).isDefined then Nil else consume(t, in)
    case Jq.Index(t, _)                         => consume(t, in)
    case Jq.Iterate(t)                          => consume(t, in)
    case Jq.Pipe(l, r)                          => uses(l, in) ++ uses(r, pathOf(l, in))
    case Jq.Compare(_, l, r)                    => consume(l, in) ++ consume(r, in)
    case Jq.And(l, r)                           => consume(l, in) ++ consume(r, in)
    case Jq.Or(l, r)                            => consume(l, in) ++ consume(r, in)
    case Jq.Select(c)                           => consume(c, in)
    case Jq.Test(Jq.Literal(_))                 => in.toList
    case Jq.Test(x)                             => in.toList ++ consume(x, in)
    case Jq.StartsWith(x)                       => in.toList ++ consume(x, in)
    case Jq.EndsWith(x)                         => in.toList ++ consume(x, in)
