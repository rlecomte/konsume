package konsume.format

import io.circe.Json
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/** Reads Avro binary encoding in place. Truncated input throws an IndexOutOfBoundsException
  * or a DecodeError: callers treat both as an undecodable record.
  */
final class AvroCursor(val bytes: Array[Byte], var pos: Int):

  /** zigzag varint, for both `int` and `long` */
  def readLong(): Long =
    var b     = bytes(pos) & 0xff
    pos += 1
    var n     = (b & 0x7f).toLong
    var shift = 7
    while (b & 0x80) != 0 do
      if shift > 63 then throw DecodeError("invalid varint")
      b = bytes(pos) & 0xff
      pos += 1
      n |= (b & 0x7fL) << shift
      shift += 7
    (n >>> 1) ^ -(n & 1)

  def readInt(): Int = readLong().toInt

  def readBoolean(): Boolean =
    val b = bytes(pos)
    pos += 1
    b != 0

  def readFloat(): Float =
    val i = (bytes(pos) & 0xff) | (bytes(pos + 1) & 0xff) << 8 | (bytes(pos + 2) & 0xff) << 16 | (bytes(pos + 3) & 0xff) << 24
    pos += 4
    java.lang.Float.intBitsToFloat(i)

  def readDouble(): Double =
    var l = 0L
    var i = 7
    while i >= 0 do
      l = (l << 8) | (bytes(pos + i) & 0xffL)
      i -= 1
    pos += 8
    java.lang.Double.longBitsToDouble(l)

  /** Length of a string or bytes, checked against what is left. */
  def readLength(): Int =
    val n = readLong()
    if n < 0 || n > bytes.length - pos then throw DecodeError(s"invalid length $n")
    n.toInt

  def skip(n: Int): Unit =
    if n < 0 || n > bytes.length - pos then throw DecodeError(s"cannot skip $n bytes")
    pos += n

  def skipVarint(): Unit =
    while (bytes(pos) & 0x80) != 0 do pos += 1
    pos += 1

/** Generic, schema-driven operations on a cursor: what the generated code falls back to for
  * values it has to materialise entirely, or schemas too big or recursive to be inlined.
  */
object AvroRuntime:

  def skip(cur: AvroCursor, schema: Schema): Unit = schema.getType match
    case NULL                => ()
    case BOOLEAN             => cur.skip(1)
    case INT | LONG | ENUM   => cur.skipVarint()
    case FLOAT               => cur.skip(4)
    case DOUBLE              => cur.skip(8)
    case STRING | BYTES      => cur.skip(cur.readLength())
    case FIXED               => cur.skip(schema.getFixedSize)
    case RECORD              => schema.getFields.forEach(f => skip(cur, f.schema))
    case UNION               => skip(cur, branch(cur, schema))
    case ARRAY               => blocks(cur)(skip(cur, schema.getElementType))
    case MAP                 => blocks(cur) { cur.skip(cur.readLength()); skip(cur, schema.getValueType) }

  /** The JSON view of the value at the cursor: must agree with [[AvroJson]]. */
  def readJson(cur: AvroCursor, schema: Schema): Json = schema.getType match
    case NULL    => Json.Null
    case BOOLEAN => Json.fromBoolean(cur.readBoolean())
    case INT     => Json.fromInt(cur.readInt())
    case LONG    => Json.fromLong(cur.readLong())
    case FLOAT   => Json.fromFloatOrNull(cur.readFloat())
    case DOUBLE  => Json.fromDoubleOrNull(cur.readDouble())
    case STRING  => Json.fromString(readString(cur))
    case ENUM    => Json.fromString(schema.getEnumSymbols.get(cur.readInt()))
    case BYTES =>
      val n = cur.readLength()
      AvroJson.binary(slice(cur, n), schema)
    case FIXED => AvroJson.binary(slice(cur, schema.getFixedSize), schema)
    case RECORD =>
      Json.fromFields(schema.getFields.asScala.map(f => f.name -> readJson(cur, f.schema)))
    case UNION => readJson(cur, branch(cur, schema))
    case ARRAY =>
      val values = Vector.newBuilder[Json]
      blocks(cur)(values += readJson(cur, schema.getElementType))
      Json.fromValues(values.result())
    case MAP =>
      val fields = Vector.newBuilder[(String, Json)]
      blocks(cur)(fields += readString(cur) -> readJson(cur, schema.getValueType))
      Json.fromFields(fields.result())

  def readString(cur: AvroCursor): String =
    val n = cur.readLength()
    val s = String(cur.bytes, cur.pos, n, UTF_8)
    cur.pos += n
    s

  private def slice(cur: AvroCursor, n: Int): Array[Byte] =
    if n > cur.bytes.length - cur.pos then throw DecodeError("truncated")
    val b = java.util.Arrays.copyOfRange(cur.bytes, cur.pos, cur.pos + n)
    cur.pos += n
    b

  private def branch(cur: AvroCursor, union: Schema): Schema =
    val i = cur.readInt()
    if i < 0 || i >= union.getTypes.size then throw DecodeError(s"invalid union index $i")
    union.getTypes.get(i)

  /** Arrays and maps: blocks of `count` items, a negative count being followed by the block size. */
  private inline def blocks(cur: AvroCursor)(inline item: Unit): Unit =
    var n = cur.readLong()
    while n != 0 do
      if n < 0 then
        cur.readLong() // block size in bytes, unused: items are read one by one
        n = -n
      var i = 0L
      while i < n do
        item
        i += 1
      n = cur.readLong()
