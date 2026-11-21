package konsume.avro

import io.circe.Json
import konsume.planned.{Demand, Path, PVal, Step}
import konsume.jq.Runtime
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** The reading plan of an Avro record, for the paths a jq program uses: which fields to read,
  * into which slots, which ones to skip, where to stop. Run by [[AvroPlanned]].
  */
private[avro] object AvroPlan:

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
    /** the number of elements of an array or a map, skipped */
    case ReadCount(slot: Int, schema: Int)
    /** some elements of an array (`.items[0]`), by increasing index, the others skipped; stops
      * after the last one unless `toEnd`
      */
    case Elements(wanted: Vector[Wanted], schema: Int, toEnd: Boolean)
    case Skip(schema: Schema)
    case Branch(index: Int, branches: Vector[List[Instr]])

  /** Element `index` of an array: read by `read`; slot `found` says whether the array had it. */
  final case class Wanted(index: Int, found: Int, read: List[Instr])

  /** Where the value of a path will be, once read. */
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
        case RECORD if !d.asValue && !d.length => record(schema, prefix, d.children, needEnd)
        case ARRAY if !d.asValue && !d.length && d.children.nonEmpty && d.children.keys.forall(_.isInstanceOf[Step.At]) =>
          elements(schema, prefix, d.children, needEnd)
        case ARRAY | MAP if d.lengthOnly => // `length`: counted, not read
          val s = slot(SlotType.Long)
          (List(ReadCount(s, schemaRef(schema))), Map(prefix -> PSized(s, rankOf(schema), None)))
        case _ =>
          val (instrs, v) = read(schema)
          (instrs, derived(v, prefix, d))

    private def record(schema: Schema, prefix: Path, children: Map[Step, Demand], needEnd: Boolean) =
      val fields = schema.getFields.asScala.toVector
      val last   = fields.lastIndexWhere(f => children.contains(Step.Key(f.name)))
      val limit  = if needEnd then fields.size - 1 else last // stop after the last needed field
      val instrs = List.newBuilder[Instr]
      var env    = Map.empty[Path, PVal]
      for i <- 0 to limit do
        val f = fields(i)
        children.get(Step.Key(f.name)) match
          case Some(d) =>
            val (is, e) = value(f.schema, prefix :+ Step.Key(f.name), d, needEnd || i < limit)
            instrs ++= is
            env ++= e
          case None => instrs += Skip(f.schema)
      for (step, d) <- children do
        step match
          // a field the writer schema does not have is null, and so is everything under it
          case Step.Key(name) if schema.getField(name) == null =>
            env ++= d.valuePaths(prefix :+ step).map(_ -> PStatic(Json.Null))
          // `.user[0]`: a record is an object, it has no elements
          case Step.At(_) =>
            env ++= d.valuePaths(prefix :+ step).map(_ -> PFail("Cannot index object with number"))
          case _ => ()
      (instrs.result(), env)

    /** `.items[0]`, `.items[2].x`: only these elements are read, each into its own slots. */
    private def elements(schema: Schema, prefix: Path, children: Map[Step, Demand], needEnd: Boolean) =
      val element = schema.getElementType
      var env     = Map.empty[Path, PVal]
      val wanted = children.toVector.collect { case (s @ Step.At(i), d) => (i, s, d) }.sortBy(_._1).map { (i, s, d) =>
        val found = slot(SlotType.Boolean)
        // the walk goes on after the element: it must be read to its end
        val (instrs, e) = value(element, prefix :+ s, d, needEnd = true)
        // an array too short: the element is null, and so is everything under it
        env ++= e.map((p, v) => p -> PChoice(found, Vector(PStatic(Json.Null), v)))
        Wanted(i, found, instrs)
      }
      (List(Elements(wanted, schemaRef(element), toEnd = needEnd)), env)

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
      (if d.asValue || d.length then Map(prefix -> v) else Map.empty) ++
        d.children.flatMap((n, cd) => derived(PField(v, n), prefix :+ n, cd))
