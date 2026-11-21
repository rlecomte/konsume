package konsume.json

import io.circe.Json
import konsume.Record
import konsume.planned.{Demand, Frame, PVal, Path, Planned, Sink, Step, Undecoded, Walk}
import konsume.jq.{Jq, Runtime}

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable.ArrayBuffer

/** Runs a jq program on JSON payloads without building a JSON tree.
  *
  * The paths the program uses are found on its syntax tree, then the payload is scanned once: at
  * each level of interest, keys are compared as bytes with the expected names, the values of the
  * others are skipped without being decoded. What a used path holds is kept in slots: its kind
  * (null, false, true, number, string, string with escapes, array, object), plus a double for a
  * number, or its position in the payload. The program then runs on these slots, as for Avro.
  *
  * JSON specifics:
  *   - the whole payload is read and validated, as the reference decoder does;
  *   - duplicate keys: the last one wins, as in jq (what was found under a key seen again is
  *     forgotten);
  *   - strings with escapes are decoded when used, the others are compared as bytes;
  *   - numbers are compared as doubles, but emitted as written.
  */
object JsonPlanned:

  def compile(program: Jq): Planned.Fn =
    val demand = Demand.of(program)
    var slots  = 0
    def slot(): Int = { slots += 1; slots - 1 }
    val root = node(demand, slot)
    val penv = values(root, Nil)
    val n    = slots
    Planned.build(program, penv, walks(root, Nil), regs => JsonFrame(n, regs)) { f =>
      java.util.Arrays.fill(f.nums, 0L) // every kind is Null: a key not found is null
      if demand == Demand.empty then f.cur.skipValue() // nothing to read, but still validate
      else readValue(root, f.cur, f)
      f.cur.end()
    }

  /** A frame with its JSON cursor, reused too. */
  final class JsonFrame(slots: Int, registers: Int) extends Frame(slots, registers):
    val cur = JsonCursor(null, 0)

    override def begin(r: Record[Any, Array[Byte]], o: ArrayBuffer[Record[Any, Array[Byte]]]): Unit =
      super.begin(r, o)
      cur.bytes = bytes
      cur.pos = 0

    override def clear(): Unit =
      super.clear()
      cur.bytes = null

  // ------------------------------------------------------------------------- the paths read

  /** A path the program uses, and the slots holding what the scan found there: its kind always,
    * its number and position only when the program uses the value itself (or its length, or
    * walks it), the number of elements of an array when it uses its length. Below it, the fields
    * (`children`) and the array elements (`elements`, by increasing index) the program uses, and
    * the element at hand when the program walks it (`each`).
    */
  private final class Node(
      val asValue: Boolean,
      val kind: Int,
      val number: Int,
      val start: Int,
      val len: Int,
      val count: Int,
      val lengthOnly: Boolean,
      val children: Array[Child],
      val elements: Array[Element],
      val each: Each | Null
  ):
    /** The kinds of everything below: reset to null before reading a container, so that nothing
      * is left from a key seen before (duplicate keys) nor from an array too short.
      */
    val descendantKinds: Array[Int] =
      children.flatMap(c => c.node.kind +: c.node.descendantKinds) ++
        elements.flatMap(e => e.node.kind +: e.node.descendantKinds) ++
        (if each == null then Array.emptyIntArray else each.node.kind +: each.node.descendantKinds)

  private final class Child(val name: String, val utf8: Array[Byte], val node: Node)
  private final class Element(val index: Int, val node: Node)
  /** The element at hand of an array walked by the program: read into the slots of `node`, or,
    * when the value is iterated on its JSON view, in `refs(held)` (kind [[Held]]).
    */
  private final class Each(val node: Node, val held: Int)

  /** The kind of an element given as a `Json` (`Walk.hold`), after the 8 kinds of the cursor. */
  private val Held = 8

  private def node(d: Demand, slot: () => Int): Node =
    // a value walked has its position (to walk it) and its value (to iterate its JSON view when
    // it is not an array): its slots only, nothing is read more
    val asValue              = d.asValue || d.length || d.children.contains(Step.Each)
    val kind                 = slot()
    val (number, start, len) = if asValue then (slot(), slot(), slot()) else (-1, -1, -1)
    val count                = if d.length then slot() else -1
    val children = d.children.toArray.collect { case (Step.Key(name), cd) =>
      Child(name, name.getBytes(UTF_8), node(cd, slot))
    }
    val elements = d.children.toArray.collect { case (Step.At(i), cd) => Element(i, node(cd, slot)) }.sortBy(_.index)
    val each     = d.children.get(Step.Each).map(cd => Each(node(cd, slot), slot())).orNull
    Node(asValue, kind, number, start, len, count, d.lengthOnly, children, elements, each)

  // ------------------------------------------------------------------------------ scanning

  private def readValue(n: Node, cur: JsonCursor, f: Frame): Unit =
    val k = cur.kind()
    f.nums(n.kind) = k
    if n.children.nonEmpty && k == JsonCursor.Object then
      forgetBelow(n, f)
      val start = cur.pos
      readObject(n, cur, f)
      if n.asValue then
        f.nums(n.start) = start
        f.nums(n.len) = cur.pos - start
    else if n.elements.nonEmpty && k == JsonCursor.Array then
      forgetBelow(n, f)
      val start = cur.pos
      val count = readArray(n, cur, f)
      if n.count >= 0 then f.nums(n.count) = count
      if n.asValue then
        f.nums(n.start) = start
        f.nums(n.len) = cur.pos - start
    else if !n.asValue then cur.skipValue()
    else if k == JsonCursor.Number then
      f.nums(n.number) = java.lang.Double.doubleToRawLongBits(cur.readNumber())
      f.nums(n.start) = cur.valStart
      f.nums(n.len) = cur.valLen
    else if k == JsonCursor.String then
      cur.readString()
      f.nums(n.start) = cur.valStart
      f.nums(n.len) = cur.valLen
      if cur.escaped then f.nums(n.kind) = JsonCursor.EscapedString
    else
      val start = cur.pos
      if k == JsonCursor.Array && n.count >= 0 then f.nums(n.count) = cur.countArray()
      else cur.skipValue()
      f.nums(n.start) = start
      f.nums(n.len) = cur.pos - start

  /** What was found below `n` before (a key seen twice, the previous array) is forgotten. */
  private def forgetBelow(n: Node, f: Frame): Unit =
    val ds = n.descendantKinds
    var i  = 0
    while i < ds.length do
      f.nums(ds(i)) = JsonCursor.Null
      i += 1

  /** Reads the wanted elements, skips the others; returns the number of elements. */
  private def readArray(n: Node, cur: JsonCursor, f: Frame): Long =
    val es = n.elements
    var i  = 0L // index of the element at the cursor
    var e  = 0  // the next wanted element
    if cur.beginArray() then
      var more = true
      while more do
        if e < es.length && es(e).index == i then
          readValue(es(e).node, cur, f)
          e += 1
        else cur.skipValue()
        i += 1
        more = cur.nextElement()
    i

  private def readObject(n: Node, cur: JsonCursor, f: Frame): Unit =
    if cur.beginObject() then
      var more = true
      while more do
        cur.readKey()
        val cs = n.children
        var i  = 0
        while i < cs.length && !cur.keyIs(cs(i).utf8, cs(i).name) do i += 1
        if i < cs.length then readValue(cs(i).node, cur, f) else cur.skipValue()
        more = cur.nextMember()

  // ------------------------------------------------------------------------ walking arrays

  /** The arrays the program walks, below `n` (at `path`). */
  private def walks(n: Node, path: Path): Map[Path, Walk] =
    val own = if n.each == null then Map.empty else Map(path -> ArrayWalk(n, n.each, Undecoded(path :+ Step.Each)))
    own ++
      n.children.flatMap(c => walks(c.node, path :+ Step.Key(c.name))) ++
      n.elements.flatMap(e => walks(e.node, path :+ Step.At(e.index))) ++
      (if n.each == null then Map.empty else walks(n.each.node, path :+ Step.Each))

  /** Walks the array found at `array` (validated when the record was read), each element read
    * into the slots of `each`. Everything below the element is forgotten first: nothing is left
    * from the previous element (`[{"xs":[1]}, 5]`: `.xs` of `5` is not `[1]`), nor from a previous
    * walk.
    */
  private final class ArrayWalk(array: Node, each: Each, element: Undecoded) extends Walk:
    def apply(f: Frame, body: Sink): Boolean =
      if f.nums(array.kind) != JsonCursor.Array then false
      else
        val cur = f.asInstanceOf[JsonFrame].cur
        cur.pos = f.int(array.start)
        if cur.beginArray() then
          var more = true
          while more do
            forgetBelow(each.node, f)
            readValue(each.node, cur, f)
            val next = cur.pos
            body(f, element)
            cur.pos = next // a walk inside the element moved the cursor
            more = cur.nextElement()
        true

    // Nothing below the element can be left from the payload: kinds are null from the start of
    // the record, and a walk around this one forgets everything below its element first.
    def hold(f: Frame, j: Json): Unit =
      f.nums(each.node.kind) = Held
      f.refs(each.held) = j

  // ------------------------------------------------------------------------- values found

  import PVal.*

  /** The value of every path the program uses, from the slots. */
  private def values(n: Node, path: Path): Map[Path, PVal] =
    val own =
      if !n.asValue then Map.empty
      else
        Map(
          path -> PChoice(
            n.kind,
            Vector(
              PStatic(Json.Null),
              PStatic(Json.False),
              PStatic(Json.True),
              PNumber(n.number, n.start, n.len),
              PString(n.start, n.len),
              PText(n.start, n.len),
              if n.count >= 0 then PSized(n.count, Runtime.ArrayRank, Option.unless(n.lengthOnly)(PSlice(n.start, n.len, Runtime.ArrayRank)))
              else PSlice(n.start, n.len, Runtime.ArrayRank),
              PSlice(n.start, n.len, Runtime.ObjectRank)
            )
          )
        )

    // `.parent.name`, `.parent[i]`: null under null (or a missing key), an error under anything
    // but an object (for a name) or an array (for an index). Under a held element, only the
    // element of a walk inside it is asked here (`.a[].b[]`), and that walk gives it
    def under(container: Int, what: String)(v: PVal): PVal =
      PChoice(
        n.kind,
        Vector.tabulate(Held + 1) {
          case JsonCursor.Null    => PStatic(Json.Null)
          case `container` | Held => v
          case _                  => PFail(s"Cannot index a value that is not $what")
        }
      )

    // `.a[]`, `.a[].b`: while the array is walked; the element is in the slots, or held as a
    // `Json` (kind `Held`), and so is everything below it, taken on that `Json`. Below a walk
    // inside the element (`.a[].b[]`), the element is the inner walk's: in its slots, or held
    // by it (it is not walked from the payload when the outer element is held).
    def walked(e: Each): Map[Path, PVal] =
      val at = path :+ Step.Each
      values(e.node, at).map { (p, v) =>
        val below = p.drop(at.size)
        if below.contains(Step.Each) then p -> v
        else
          val onHeld = below.foldLeft(PAny(e.held): PVal)(PField(_, _))
          p -> PChoice(e.node.kind, Vector.tabulate(Held + 1)(k => if k == Held then onHeld else v))
      }

    own ++
      n.children.flatMap(c => values(c.node, path :+ Step.Key(c.name)).map((p, v) => p -> under(JsonCursor.Object, "an object with a string")(v))) ++
      n.elements.flatMap(e => values(e.node, path :+ Step.At(e.index)).map((p, v) => p -> under(JsonCursor.Array, "an array with a number")(v))) ++
      (if n.each == null then Map.empty else walked(n.each))
