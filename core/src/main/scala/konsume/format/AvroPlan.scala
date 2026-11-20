package konsume.format

import io.circe.Json
import konsume.jq.Runtime
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** The reading plan of an Avro record, for the paths a jq program uses: which fields to read,
  * into which slots, which ones to skip, where to stop. Run by [[AvroPlanned]].
  */
private[format] object AvroPlan:

  type Path = List[String]

  /** The paths a program uses, as a tree: `asValue` when it uses the value itself, not only
    * some of its fields.
    */
  final case class Demand(asValue: Boolean, children: Map[String, Demand]):
    def valuePaths(prefix: Path): List[Path] =
      (if asValue then List(prefix) else Nil) ++ children.toList.flatMap((n, d) => d.valuePaths(prefix :+ n))

  object Demand:
    val empty = Demand(false, Map.empty)
    def of(paths: Iterable[Path]): Demand = paths.foldLeft(empty)(add)
    private def add(d: Demand, path: Path): Demand = path match
      case Nil       => d.copy(asValue = true)
      case n :: rest => d.copy(children = d.children.updated(n, add(d.children.getOrElse(n, empty), rest)))

  enum SlotType:
    case Long, Double, Boolean, Int, Json

  enum Instr:
    case ReadLong(slot: Int)
    case ReadFloat(slot: Int)
    case ReadDouble(slot: Int)
    case ReadBoolean(slot: Int)
    case ReadIndex(slot: Int)
    case ReadString(off: Int, len: Int)
    case ReadJson(slot: Int, schema: Int)
    case Skip(schema: Schema)
    case Branch(index: Int, branches: Vector[List[Instr]])

  /** Where the value of a path will be, once read. */
  enum PVal:
    case PStatic(value: Json)
    case PLong(slot: Int)
    case PDouble(slot: Int, isFloat: Boolean)
    case PBoolean(slot: Int)
    case PString(off: Int, len: Int)
    case PJson(slot: Int, rank: Int)
    case PEnum(slot: Int, symbols: Vector[String])
    case PChoice(index: Int, branches: Vector[PVal])
    /** `.name` of a value read entirely: done on its JSON view, when used */
    case PField(parent: PVal, name: String)

  import Instr.*, PVal.*

  final class Planner(schemas: ArrayBuffer[Schema]):
    val slots = ArrayBuffer.empty[SlotType]

    private def slot(t: SlotType): Int =
      slots += t
      slots.size - 1

    def schemaRef(s: Schema): Int =
      val i = schemas.indexWhere(_ eq s)
      if i >= 0 then i
      else
        schemas += s
        schemas.size - 1

    /** Reads a value of `schema` at `prefix`; `needEnd`: the cursor must end after it. */
    def value(schema: Schema, prefix: Path, d: Demand, needEnd: Boolean): (List[Instr], Map[Path, PVal]) =
      schema.getType match
        case NULL => (Nil, d.valuePaths(prefix).map(_ -> PStatic(Json.Null)).toMap)
        case UNION =>
          val index    = slot(SlotType.Int)
          val branches = schema.getTypes.asScala.toVector.map(value(_, prefix, d, needEnd))
          val env      = d.valuePaths(prefix).map(p => p -> PChoice(index, branches.map(_._2(p)))).toMap
          (List(ReadIndex(index), Branch(index, branches.map(_._1))), env)
        case RECORD if !d.asValue => record(schema, prefix, d.children, needEnd)
        case _ =>
          val (instrs, v) = read(schema)
          (instrs, derived(v, prefix, d))

    private def record(schema: Schema, prefix: Path, children: Map[String, Demand], needEnd: Boolean) =
      val fields = schema.getFields.asScala.toVector
      val last   = fields.lastIndexWhere(f => children.contains(f.name))
      val limit  = if needEnd then fields.size - 1 else last // stop after the last needed field
      val instrs = List.newBuilder[Instr]
      var env    = Map.empty[Path, PVal]
      for i <- 0 to limit do
        val f = fields(i)
        children.get(f.name) match
          case Some(d) =>
            val (is, e) = value(f.schema, prefix :+ f.name, d, needEnd || i < limit)
            instrs ++= is
            env ++= e
          case None => instrs += Skip(f.schema)
      // a field the writer schema does not have is null, and so is everything under it
      for (name, d) <- children if schema.getField(name) == null do
        env ++= d.valuePaths(prefix :+ name).map(_ -> PStatic(Json.Null))
      (instrs.result(), env)

    private def read(schema: Schema): (List[Instr], PVal) = schema.getType match
      case BOOLEAN    => val s = slot(SlotType.Boolean); (List(ReadBoolean(s)), PBoolean(s))
      case INT | LONG => val s = slot(SlotType.Long); (List(ReadLong(s)), PLong(s))
      case FLOAT      => val s = slot(SlotType.Double); (List(ReadFloat(s)), PDouble(s, isFloat = true))
      case DOUBLE     => val s = slot(SlotType.Double); (List(ReadDouble(s)), PDouble(s, isFloat = false))
      case ENUM =>
        val s = slot(SlotType.Int)
        (List(ReadIndex(s)), PEnum(s, schema.getEnumSymbols.asScala.toVector))
      case STRING =>
        val off = slot(SlotType.Int)
        val len = slot(SlotType.Int)
        (List(ReadString(off, len)), PString(off, len))
      case _ => // records used as a whole, arrays, maps, bytes, fixed: their JSON view
        val s = slot(SlotType.Json)
        (List(ReadJson(s, schemaRef(schema))), PJson(s, rankOf(schema)))

    /** The rank in jq's order of the JSON view of values of `schema` (not null, union, boolean). */
    private def rankOf(schema: Schema): Int = schema.getType match
      case ARRAY                                                   => Runtime.ArrayRank
      case RECORD | MAP                                            => Runtime.ObjectRank
      case _ if schema.getLogicalType.isInstanceOf[org.apache.avro.LogicalTypes.Decimal] => Runtime.NumberRank
      case BYTES | FIXED                                           => Runtime.StringRank // base64
      case _                                                       => Runtime.NumberRank

    private def derived(v: PVal, prefix: Path, d: Demand): Map[Path, PVal] =
      (if d.asValue then Map(prefix -> v) else Map.empty) ++
        d.children.flatMap((n, cd) => derived(PField(v, n), prefix :+ n, cd))
