package buildyourown

import scala.collection.mutable

import Instr.*
import PVal.*
import Schema.*

/** One step of a reading plan, run on the record's bytes, in order. */
enum Instr:
  case ReadInt(slot: Int)          // the int at the cursor, into a slot
  case ReadStr(off: Int, len: Int) // the offset and length of the string at the cursor
  case Skip(schema: Schema)        // past a value, without decoding it

/** How to read the paths a program uses, for one schema: the instructions, run in order on each
  * record, and where each path's value ends up (`values`). Only ints and strings go into slots:
  * a record used whole (`.user`) has no value here, and a program using one is left to the
  * interpreter.
  */
final case class Plan(instrs: List[Instr], values: Map[Path, PVal], slots: Int):

  /** Runs the instructions on the frame's bytes: fills the slots. */
  def read(f: Frame): Unit =
    val r = Binary.Reader(f.bytes, 0)
    instrs.foreach {
      case ReadInt(s) => f.nums(s) = r.int()
      case ReadStr(off, len) =>
        f.nums(off) = r.pos + 1
        f.nums(len) = r.bytes(r.pos) & 0xff
        r.skip(SString)
      case Skip(s) => r.skip(s)
    }

object Plan:

  def apply(schema: Schema, demand: Set[Path]): Plan =
    val b      = Builder(demand)
    val instrs = b.value(schema, Nil, needEnd = false)
    Plan(instrs, b.values.toMap, b.slots)

  private final class Builder(demand: Set[Path]):
    var slots  = 0
    val values = mutable.Map.empty[Path, PVal]

    private def slot(): Int =
      slots += 1
      slots - 1

    /** The demanded paths strictly below `path`. */
    private def below(path: Path): Set[Path] = demand.filter(p => p.length > path.length && p.startsWith(path))

    /** The instructions reading the value of `schema` at `path`, the cursor being on it.
      * `needEnd`: something after it is read, so the cursor must end past it.
      */
    def value(schema: Schema, path: Path, needEnd: Boolean): List[Instr] = schema match
      case SInt | SString =>
        below(path).foreach(values(_) = PNull) // `.name.x`: a field of a string is null
        if !demand.contains(path) then List(Skip(schema))
        else if schema == SInt then { val s = slot(); values(path) = PInt(s); List(ReadInt(s)) }
        else { val (o, l) = (slot(), slot()); values(path) = PStr(o, l); List(ReadStr(o, l)) }
      case SRecord(fields) =>
        // a demanded field the schema does not have: null, and so is everything below it
        for p <- below(path) if !fields.exists(_._1 == p(path.length)) do values(p) = PNull
        val needed = fields.lastIndexWhere((name, _) => below(path).exists(_(path.length) == name))
        if needed < 0 then (if needEnd then List(Skip(schema)) else Nil)
        else
          // the fields in order, up to the last one needed (to the end if something follows)
          val limit = if needEnd then fields.size - 1 else needed
          fields.take(limit + 1).zipWithIndex.flatMap { case ((name, s), i) =>
            val p = path :+ name
            if demand.contains(p) || below(p).nonEmpty then value(s, p, needEnd || i < limit)
            else List(Skip(s))
          }
