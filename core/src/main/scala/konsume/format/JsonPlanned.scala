package konsume.format

import io.circe.Json
import konsume.Record
import konsume.format.Planned.{Demand, Frame, PVal, Path}
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
    val demand = Planned.demandOf(program)
    var slots  = 0
    def slot(): Int = { slots += 1; slots - 1 }
    val root = node(demand, slot)
    val penv = values(root, Nil)
    val n    = slots
    Planned.build(program, penv, regs => JsonFrame(n, regs)) { f =>
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
    * its number and position only when the program uses the value itself.
    */
  private final class Node(
      val asValue: Boolean,
      val kind: Int,
      val number: Int,
      val start: Int,
      val len: Int,
      val children: Array[Child]
  ):
    /** The kinds of everything below: forgotten when the key is seen again. */
    val descendantKinds: Array[Int] = children.flatMap(c => c.node.kind +: c.node.descendantKinds)

  private final class Child(val name: String, val utf8: Array[Byte], val node: Node)

  private def node(d: Demand, slot: () => Int): Node =
    val kind                 = slot()
    val (number, start, len) = if d.asValue then (slot(), slot(), slot()) else (-1, -1, -1)
    val children             = d.children.toArray.map((name, cd) => Child(name, name.getBytes(UTF_8), node(cd, slot)))
    Node(d.asValue, kind, number, start, len, children)

  // ------------------------------------------------------------------------------ scanning

  private def readValue(n: Node, cur: JsonCursor, f: Frame): Unit =
    val k = cur.kind()
    f.nums(n.kind) = k
    if n.children.nonEmpty && k == JsonCursor.Object then
      // this key may have been seen before: what was found under it is forgotten
      val ds = n.descendantKinds
      var i  = 0
      while i < ds.length do
        f.nums(ds(i)) = JsonCursor.Null
        i += 1
      val start = cur.pos
      readObject(n, cur, f)
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
      cur.skipValue()
      f.nums(n.start) = start
      f.nums(n.len) = cur.pos - start

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
              PSlice(n.start, n.len, Runtime.ArrayRank),
              PSlice(n.start, n.len, Runtime.ObjectRank)
            )
          )
        )

    // `.parent.name`: null under null (or a missing key), an error under anything but an object
    def under(v: PVal): PVal =
      PChoice(
        n.kind,
        Vector.tabulate(8) {
          case JsonCursor.Null   => PStatic(Json.Null)
          case JsonCursor.Object => v
          case _                 => PFail("Cannot index a value that is not an object with a string")
        }
      )

    own ++ n.children.flatMap(c => values(c.node, path :+ c.name).map((p, v) => p -> under(v)))
