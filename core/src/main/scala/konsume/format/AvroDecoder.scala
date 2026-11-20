package konsume.format

import io.circe.Json
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DecoderFactory

import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

/** Reference Avro decoder: Confluent-framed bytes => GenericRecord => JSON view.
  *
  * Correct but slow (it builds a GenericRecord, then a Json): it is the oracle and the fallback
  * of the planned engine.
  *
  * @param schemas
  *   writer schemas by registry id. Fetching them is I/O: the consumer does it (see
  *   `Confluent.schemaIds`) before handing the batch to the pure pipeline.
  */
final class AvroDecoder(schemas: Int => Option[Schema]) extends Decoder:

  private val readers = ConcurrentHashMap[Int, (Schema, GenericDatumReader[Any])]()

  def decode(bytes: Array[Byte]): Json =
    val id = Confluent.schemaId(bytes)
    var entry = readers.get(id)
    if entry == null then
      val schema = schemas(id).getOrElse(throw DecodeError(s"unknown schema id $id"))
      entry = readers.computeIfAbsent(id, _ => (schema, GenericDatumReader[Any](schema)))
    val (schema, reader) = entry
    val datum =
      try
        val in = DecoderFactory.get.binaryDecoder(bytes, Confluent.HeaderSize, bytes.length - Confluent.HeaderSize, null)
        reader.read(null, in)
      catch case NonFatal(e) => throw DecodeError(s"invalid Avro payload for schema $id", e)
    AvroJson.toJson(datum, schema)
