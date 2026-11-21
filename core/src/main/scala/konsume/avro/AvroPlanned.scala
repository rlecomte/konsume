package konsume.avro

import io.circe.Json
import konsume.DecodeError
import konsume.Record
import konsume.avro.AvroPlan.{Instr, Planner}
import konsume.planned.{Demand, Frame, JsonOut, Planned, Sink, Undecoded, Walk, Encoded as PEncoded, Step as PathStep}
import konsume.jq.Jq
import org.apache.avro.Schema

import scala.collection.mutable.ArrayBuffer

/** Runs a jq program on Avro bytes, for one writer schema, without decoding the record.
  *
  *  1. the paths whose values the program uses (`.user.age`) are found on its syntax tree
  *     ([[konsume.planned.Demand]]);
  *  2. a reading plan is derived from the writer schema ([[AvroPlan]]): needed fields into slots,
  *     the others skipped without being decoded, stop after the last needed one;
  *  3. the plan is run by a small interpreter, and the program is turned into closures once,
  *     with comparisons specialised on the type of the slot they read (a long, UTF-8 bytes in
  *     place, a union index...).
  *
  * Building a program takes well under a millisecond and loads no class. A record kept
  * unchanged is emitted with its original bytes; any other output as JSON.
  *
  * This replaces a staged engine (bytecode generated at runtime): see docs/staged-engine.md.
  */
object AvroPlanned:

  def compile(program: Jq, schema: Schema): Planned.Fn =
    val schemas      = ArrayBuffer.empty[Schema]
    val planner      = Planner(schemas)
    val (plan, penv) = planner.value(schema, Nil, Demand.of(program), needEnd = false)
    val read         = steps(plan, schemas.toVector)
    val walks        = planner.walks.map(w => w.path -> ArrayWalk(w.pos, steps(w.read, schemas.toVector), Undecoded(w.path :+ PathStep.Each))).toMap
    val slots        = planner.slots.size
    Planned.build(program, penv, walks, regs => AvroFrame(slots, regs))(f => read(f.cur, f))

  /** A frame with its Avro cursors, reused too: `cur` reads the record, `aux` the values left in
    * the payload, decoded while the program runs (it may be walking an array with `cur`).
    */
  final class AvroFrame(slots: Int, registers: Int) extends Frame(slots, registers):
    val cur = AvroCursor(null, 0)
    val aux = AvroCursor(null, 0)

    override def begin(r: Record[Any, Array[Byte]], o: ArrayBuffer[Record[Any, Array[Byte]]]): Unit =
      super.begin(r, o)
      cur.bytes = bytes
      cur.pos = Confluent.HeaderSize
      aux.bytes = bytes

    override def clear(): Unit =
      super.clear()
      cur.bytes = null
      aux.bytes = null

  /** A value of `schema` left in the payload: decoded from its position when used, or written as
    * JSON directly from it.
    */
  private[avro] final class Encoded(schema: Schema) extends PEncoded:
    private val writer = AvroWriter(schema)

    def json(f: Frame, pos: Int): Json =
      val cur = f.asInstanceOf[AvroFrame].aux
      cur.pos = pos
      AvroRuntime.readJson(cur, schema)

    def write(f: Frame, pos: Int, out: JsonOut): Unit =
      val cur = f.asInstanceOf[AvroFrame].aux
      cur.pos = pos
      writer(cur, out)

  // ---------------------------------------------------------------------- reading plan

  trait Step:
    def apply(cur: AvroCursor, f: Frame): Unit

  private def steps(is: List[Instr], schemas: Vector[Schema]): Step =
    val all = is.map(step(_, schemas)).toArray
    if all.length == 1 then all(0)
    else
      (cur, f) =>
        var i = 0
        while i < all.length do
          all(i)(cur, f)
          i += 1

  import Instr.*

  private def step(i: Instr, schemas: Vector[Schema]): Step = i match
    case ReadLong(s)    => (cur, f) => f.nums(s) = cur.readLong()
    case ReadFloat(s)   => (cur, f) => f.nums(s) = java.lang.Double.doubleToRawLongBits(cur.readFloat().toDouble)
    case ReadDouble(s)  => (cur, f) => f.nums(s) = java.lang.Double.doubleToRawLongBits(cur.readDouble())
    case ReadBoolean(s) => (cur, f) => f.nums(s) = if cur.readBoolean() then 1L else 0L
    case ReadIndex(s)   => (cur, f) => f.nums(s) = cur.readInt()
    case ReadString(off, len) =>
      (cur, f) =>
        val n = cur.readLength()
        f.nums(off) = cur.pos
        f.nums(len) = n
        cur.skip(n)
    case ReadPos(s)      => (cur, f) => f.nums(s) = cur.pos
    case ReadEncoded(pos, cache) =>
      (cur, f) =>
        f.nums(pos) = cur.pos
        f.refs(cache) = null
    case ReadCount(s, k) => val schema = schemas(k); (cur, f) => f.nums(s) = AvroRuntime.count(cur, schema)
    case Elements(wanted, k, toEnd) =>
      ElementsStep(wanted.map(_.index).toArray, wanted.map(_.found).toArray, wanted.map(w => steps(w.read, schemas)).toArray, skip(schemas(k)), toEnd)
    case Skip(schema)   => skip(schema)
    case Branch(s, bs) =>
      val branches = bs.map(steps(_, schemas)).toArray
      (cur, f) =>
        val i = f.int(s)
        if i < 0 || i >= branches.length then throw DecodeError("invalid union index")
        branches(i)(cur, f)

  /** Walks an array block by block (`count, elements..., count, elements..., 0`), reads the
    * wanted elements (`indices`, increasing) and skips the others. A negative count is followed
    * by the size of the block in bytes: a block with no wanted element is then skipped at once.
    */
  private final class ElementsStep(indices: Array[Int], found: Array[Int], reads: Array[Step], skipElement: Step, toEnd: Boolean)
      extends Step:
    def apply(cur: AvroCursor, f: Frame): Unit =
      var j = 0
      while j < found.length do
        f.nums(found(j)) = 0L
        j += 1
      var next = 0  // the next wanted element
      var i    = 0L // index of the next element in the array
      var n    = cur.readLong()
      while n != 0 && (toEnd || next < indices.length) do
        val sized = n < 0
        if sized then n = -n
        val size = if sized then cur.readLong() else -1L
        if sized && (next == indices.length || indices(next) >= i + n) then cur.skip(size.toInt) // nothing wanted in it
        else
          var b = 0L
          while b < n do
            if next < indices.length && indices(next) == i + b then
              reads(next)(cur, f)
              f.nums(found(next)) = 1L
              next += 1
              if next == indices.length && !toEnd then return // the last one: stop here
            else skipElement(cur, f)
            b += 1
        i += n
        n = cur.readLong()

  /** Walks an array from its position (slot `pos`), block by block: each element is read by
    * `read`, then passed to the program (`element`, the path being walked). The program may walk
    * an array inside the element (`.rs[] | .xs[]`), which moves the cursor: it is put back after
    * the element before going on.
    */
  private final class ArrayWalk(pos: Int, read: Step, element: Undecoded) extends Walk:
    def apply(f: Frame, body: Sink): Boolean =
      val cur = f.asInstanceOf[AvroFrame].cur
      cur.pos = f.int(pos)
      var n = cur.readLong()
      while n != 0 do
        if n < 0 then
          n = -n
          cur.readLong() // the size of the block in bytes: every element is read anyway
        var b = 0L
        while b < n do
          read(cur, f)
          val next = cur.pos
          body(f, element)
          cur.pos = next
          b += 1
        n = cur.readLong()
      true

    // the schema says it is an array: always walked
    def hold(f: Frame, element: Json): Unit = throw IllegalStateException("internal error: an Avro array is always walked")

  /** Fixed-size and string skips inline; anything bigger by the generic schema walker. */
  private def skip(schema: Schema): Step =
    import Schema.Type.*
    schema.getType match
      case NULL                => (_, _) => ()
      case BOOLEAN             => (cur, _) => cur.skip(1)
      case INT | LONG | ENUM   => (cur, _) => cur.skipVarint()
      case FLOAT               => (cur, _) => cur.skip(4)
      case DOUBLE              => (cur, _) => cur.skip(8)
      case STRING | BYTES      => (cur, _) => cur.skip(cur.readLength())
      case FIXED               => val n = schema.getFixedSize; (cur, _) => cur.skip(n)
      case _                   => (cur, _) => AvroRuntime.skip(cur, schema)

