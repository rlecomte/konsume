package konsume.avro

import io.circe.Json
import konsume.DecodeError
import konsume.planned.JsonOut
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/** Writes an Avro value as JSON text, directly from its bytes: the very bytes the reference
  * writes for it (`AvroRuntime.readJson(...).noSpaces`, see [[AvroJson]] for the JSON view),
  * without building a `Json`. Built once per schema, as closures; shared by the threads (what a
  * write needs on its own lives in the `JsonOut`).
  *
  * A few values go through their JSON view: `bytes` and `fixed` (base64, or a decimal number),
  * strings that are not well-formed UTF-8 (decoded with U+FFFD replacements).
  *
  * Maps differ from the reference, which takes them from Apache's reader in a `HashMap`: their
  * keys are written in the order of the payload (the reference's is the order of the hash
  * table), and a key seen twice is written twice (Avro writers write maps from maps: never).
  */
private[avro] trait AvroWriter:
  def apply(cur: AvroCursor, out: JsonOut): Unit

private[avro] object AvroWriter:

  def apply(schema: Schema): AvroWriter = build(schema, java.util.IdentityHashMap())

  private def bytesOf(s: String) = s.getBytes(UTF_8)
  private val Null  = bytesOf("null")
  private val True  = bytesOf("true")
  private val False = bytesOf("false")

  /** A record that contains itself: its writer is used while being built. */
  private final class Ref extends AvroWriter:
    var writer: AvroWriter = null
    def apply(cur: AvroCursor, out: JsonOut): Unit = writer(cur, out)

  private def build(schema: Schema, records: java.util.IdentityHashMap[Schema, AvroWriter]): AvroWriter =
    schema.getType match
      case NULL    => (_, out) => out.bytes(Null)
      case BOOLEAN => (cur, out) => out.bytes(if cur.readBoolean() then True else False)
      case INT     => (cur, out) => out.long(cur.readInt().toLong) // an int, as the reference reads it
      case LONG    => (cur, out) => out.long(cur.readLong())
      case FLOAT   => (cur, out) => out.float(cur.readFloat())
      case DOUBLE  => (cur, out) => out.double(cur.readDouble())
      case STRING  => (cur, out) => string(cur, out)
      case ENUM =>
        val symbols = schema.getEnumSymbols.asScala.map(s => bytesOf(Json.fromString(s).noSpaces)).toArray
        (cur, out) => out.bytes(symbols(cur.readInt()))
      case BYTES | FIXED => (cur, out) => out.json(AvroRuntime.readJson(cur, schema)) // base64 or a decimal
      case UNION =>
        val branches = schema.getTypes.asScala.map(build(_, records)).toArray
        (cur, out) =>
          val i = cur.readInt()
          if i < 0 || i >= branches.length then throw DecodeError(s"invalid union index $i")
          branches(i)(cur, out)
      case RECORD =>
        records.get(schema) match
          case null =>
            val ref = Ref()
            records.put(schema, ref)
            val fields = schema.getFields.asScala.toArray
            // `{"a":`, then `,"b":` before the next ones
            val names   = fields.zipWithIndex.map((f, i) => bytesOf((if i == 0 then "{" else ",") + Json.fromString(f.name).noSpaces + ":"))
            val writers = fields.map(f => build(f.schema, records))
            ref.writer = (cur, out) =>
              if writers.isEmpty then out.byte('{')
              var i = 0
              while i < writers.length do
                out.bytes(names(i))
                writers(i)(cur, out)
                i += 1
              out.byte('}')
            ref
          case w => w
      case ARRAY =>
        val element = build(schema.getElementType, records)
        (cur, out) =>
          out.byte('[')
          var first = true
          var n     = cur.readLong()
          while n != 0 do
            if n < 0 then
              cur.readLong() // the size of the block in bytes: every element is written anyway
              n = -n
            var b = 0L
            while b < n do
              if !first then out.byte(',')
              first = false
              element(cur, out)
              b += 1
            n = cur.readLong()
          out.byte(']')
      case MAP => MapWriter(build(schema.getValueType, records))

  /** A string: as is when well-formed UTF-8, otherwise decoded as the reference does. */
  private def string(cur: AvroCursor, out: JsonOut): Unit =
    val n = cur.readLength()
    if !out.utf8String(cur.bytes, cur.pos, n) then out.string(String(cur.bytes, cur.pos, n, UTF_8))
    cur.pos += n

  /** `{"key":value,...}`, the keys in the order of the payload. */
  private final class MapWriter(value: AvroWriter) extends AvroWriter:
    def apply(cur: AvroCursor, out: JsonOut): Unit =
      out.byte('{')
      var first = true
      var n     = cur.readLong()
      while n != 0 do
        if n < 0 then
          cur.readLong() // the size of the block in bytes: every entry is written anyway
          n = -n
        var b = 0L
        while b < n do
          if !first then out.byte(',')
          first = false
          string(cur, out)
          out.byte(':')
          value(cur, out)
          b += 1
        n = cur.readLong()
      out.byte('}')
