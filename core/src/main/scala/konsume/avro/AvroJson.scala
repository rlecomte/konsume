package konsume.avro

import io.circe.Json
import org.apache.avro.{LogicalTypes, Schema}
import org.apache.avro.Schema.Type.*
import org.apache.avro.generic.{GenericData, GenericFixed, IndexedRecord}

import java.nio.ByteBuffer
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** The JSON view of an Avro value, the one jq programs are written against.
  *
  *   - unions are unwrapped: `["null","string"]` gives `null` or `"x"` (not `{"string":"x"}`);
  *   - enums give strings, maps give objects, records give objects in schema order;
  *   - `bytes` and `fixed` give base64 strings;
  *   - `decimal` gives a number, the other logical types give their underlying value
  *     (`timestamp-millis`: a number of milliseconds, `date`: a number of days, `uuid`: a string);
  *   - NaN and infinite floats give `null`.
  */
object AvroJson:

  /** `datum` as returned by a `GenericDatumReader` without conversions. */
  def toJson(datum: Any, schema: Schema): Json = schema.getType match
    case NULL    => Json.Null
    case BOOLEAN => Json.fromBoolean(datum.asInstanceOf[Boolean])
    case INT     => Json.fromInt(datum.asInstanceOf[Int])
    case LONG    => Json.fromLong(datum.asInstanceOf[Long])
    case FLOAT   => Json.fromFloatOrNull(datum.asInstanceOf[Float])
    case DOUBLE  => Json.fromDoubleOrNull(datum.asInstanceOf[Double])
    case STRING  => Json.fromString(datum.toString)
    case ENUM    => Json.fromString(datum.toString)
    case BYTES =>
      val buf   = datum.asInstanceOf[ByteBuffer].duplicate()
      val bytes = new Array[Byte](buf.remaining)
      buf.get(bytes)
      binary(bytes, schema)
    case FIXED => binary(datum.asInstanceOf[GenericFixed].bytes(), schema)
    case ARRAY =>
      val element = schema.getElementType
      Json.fromValues(datum.asInstanceOf[java.util.Collection[?]].asScala.map(toJson(_, element)))
    case MAP =>
      val values = schema.getValueType
      Json.fromFields(datum.asInstanceOf[java.util.Map[?, ?]].asScala.map((k, v) => k.toString -> toJson(v, values)))
    case RECORD =>
      val record = datum.asInstanceOf[IndexedRecord]
      Json.fromFields(schema.getFields.asScala.map(f => f.name -> toJson(record.get(f.pos), f.schema)))
    case UNION =>
      toJson(datum, schema.getTypes.get(GenericData.get.resolveUnion(schema, datum)))

  private[avro] def binary(bytes: Array[Byte], schema: Schema): Json = schema.getLogicalType match
    case d: LogicalTypes.Decimal => Json.fromBigDecimal(BigDecimal(BigInt(bytes), d.getScale))
    case _                       => Json.fromString(Base64.getEncoder.encodeToString(bytes))
