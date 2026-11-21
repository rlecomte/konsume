package konsume.avro

import konsume.DecodeError
import io.circe.Json
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/** Generic, schema-driven operations on a cursor: skipping a value, or reading it entirely as
  * JSON, for the values the reading plan does not split into slots.
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
    case RECORD =>
      val fields = schema.getFields // no lambda: skipping must not allocate
      var i      = 0
      while i < fields.size do
        skip(cur, fields.get(i).schema)
        i += 1
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

  /** The number of elements of the array or map at the cursor, skipped. */
  def count(cur: AvroCursor, schema: Schema): Long =
    var n = 0L
    schema.getType match
      case ARRAY => blocks(cur) { skip(cur, schema.getElementType); n += 1 }
      case _     => blocks(cur) { cur.skip(cur.readLength()); skip(cur, schema.getValueType); n += 1 }
    n

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
