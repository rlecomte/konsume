package konsume.format

import io.circe.Json
import konsume.jq.Gen

import scala.quoted.*

/** What the staged decoders of every format share. */
private[format] object Plan:

  /** The paths a program uses, as a tree: `asValue` when it uses the value itself, not only
    * some of its fields.
    */
  final case class Demand(asValue: Boolean, children: Map[String, Demand]):
    def valuePaths(prefix: Gen.Path): List[Gen.Path] =
      (if asValue then List(prefix) else Nil) ++ children.toList.flatMap((n, d) => d.valuePaths(prefix :+ n))

  object Demand:
    val empty = Demand(false, Map.empty)
    def of(paths: Iterable[Gen.Path]): Demand = paths.foldLeft(empty)(add)
    private def add(d: Demand, path: Gen.Path): Demand = path match
      case Nil       => d.copy(asValue = true)
      case n :: rest => d.copy(children = d.children.updated(n, add(d.children.getOrElse(n, empty), rest)))

  /** The paths `program` uses, found by generating it once in recording mode. */
  def demand(program: konsume.jq.Jq, consts: Gen.Constants)(using Quotes): Demand =
    import Gen.*
    val used = scala.collection.mutable.Set.empty[Path]
    given Ctx = Ctx(consts, pathMode = true, recorder = Some(used))
    val root  = SVal.PathRef(Nil)
    gen(program, In.root(root))(v => { if v ne root then materialize(v); '{ () } })
    Demand.of(used)

  enum SlotType:
    case Long, Double, Boolean, Int, Json

  /** A local variable of the generated code: how to read it, how to assign it. */
  type Slot = (Expr[Any], Expr[Any] => Expr[Unit])

  /** `var s0 = ...; var s1 = ...; body` */
  def declare(types: List[(SlotType, Int)], acc: Map[Int, Slot] = Map.empty)(body: Map[Int, Slot] => Expr[Unit])(using
      Quotes
  ): Expr[Unit] = types match
    case Nil => body(acc)
    case (t, id) :: rest =>
      t match
        case SlotType.Long =>
          '{ var s = 0L; ${ declare(rest, acc + (id -> ('s, v => '{ s = ${ v.asExprOf[Long] } })))(body) } }
        case SlotType.Double =>
          '{ var s = 0.0; ${ declare(rest, acc + (id -> ('s, v => '{ s = ${ v.asExprOf[Double] } })))(body) } }
        case SlotType.Boolean =>
          '{ var s = false; ${ declare(rest, acc + (id -> ('s, v => '{ s = ${ v.asExprOf[Boolean] } })))(body) } }
        case SlotType.Int =>
          '{ var s = 0; ${ declare(rest, acc + (id -> ('s, v => '{ s = ${ v.asExprOf[Int] } })))(body) } }
        case SlotType.Json =>
          '{ var s: Json = null; ${ declare(rest, acc + (id -> ('s, v => '{ s = ${ v.asExprOf[Json] } })))(body) } }
