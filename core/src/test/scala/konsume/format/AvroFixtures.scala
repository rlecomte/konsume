package konsume.format

import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory

import java.io.ByteArrayOutputStream

object AvroFixtures:
  def schema(json: String): Schema = Schema.Parser().parse(json)

  def encode(schemaId: Int, record: GenericRecord): Array[Byte] =
    val out     = ByteArrayOutputStream()
    val encoder = EncoderFactory.get.binaryEncoder(out, null)
    GenericDatumWriter[GenericRecord](record.getSchema).write(record, encoder)
    encoder.flush()
    Confluent.frame(schemaId, out.toByteArray)
