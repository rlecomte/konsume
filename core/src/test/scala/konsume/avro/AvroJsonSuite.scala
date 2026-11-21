package konsume.avro

import io.circe.Json
import io.circe.parser.parse
import konsume.DecodeError
import org.apache.avro.generic.{GenericData, GenericRecordBuilder}

import java.math.BigInteger
import java.nio.ByteBuffer

class AvroJsonSuite extends munit.FunSuite:

  private val schema = AvroFixtures.schema("""
    {"type": "record", "name": "All", "fields": [
      {"name": "n",    "type": "null"},
      {"name": "b",    "type": "boolean"},
      {"name": "i",    "type": "int"},
      {"name": "l",    "type": "long"},
      {"name": "f",    "type": "float"},
      {"name": "d",    "type": "double"},
      {"name": "nan",  "type": "double"},
      {"name": "s",    "type": "string"},
      {"name": "by",   "type": "bytes"},
      {"name": "fx",   "type": {"type": "fixed", "name": "F2", "size": 2}},
      {"name": "e",    "type": {"type": "enum", "name": "Color", "symbols": ["RED", "GREEN"]}},
      {"name": "a",    "type": {"type": "array", "items": "int"}},
      {"name": "m",    "type": {"type": "map", "values": "string"}},
      {"name": "u1",   "type": ["null", "string"]},
      {"name": "u2",   "type": ["null", "string"]},
      {"name": "u3",   "type": ["int", "string"]},
      {"name": "dec",  "type": {"type": "bytes", "logicalType": "decimal", "precision": 6, "scale": 2}},
      {"name": "ts",   "type": {"type": "long", "logicalType": "timestamp-millis"}},
      {"name": "date", "type": {"type": "int", "logicalType": "date"}},
      {"name": "uuid", "type": {"type": "string", "logicalType": "uuid"}},
      {"name": "rec",  "type": {"type": "record", "name": "Inner", "fields": [{"name": "x", "type": "int"}]}}
    ]}""")

  test("JSON view of every Avro type") {
    val record = GenericRecordBuilder(schema)
      .set("n", null)
      .set("b", true)
      .set("i", 42)
      .set("l", 1L << 40)
      .set("f", 1.5f)
      .set("d", 2.25)
      .set("nan", Double.NaN)
      .set("s", "héllo")
      .set("by", ByteBuffer.wrap(Array[Byte](1, 2, 3)))
      .set("fx", GenericData.Fixed(schema.getField("fx").schema, Array[Byte](-1, 0)))
      .set("e", GenericData.EnumSymbol(schema.getField("e").schema, "GREEN"))
      .set("a", java.util.List.of(1, 2))
      .set("m", java.util.Map.of("k", "v"))
      .set("u1", null)
      .set("u2", "x")
      .set("u3", "y")
      .set("dec", ByteBuffer.wrap(BigInteger.valueOf(12345).toByteArray))
      .set("ts", 1700000000000L)
      .set("date", 19000)
      .set("uuid", "6f1c9c1e-1b1a-4c4e-9a7e-2f1f0e2d3c4b")
      .set("rec", GenericRecordBuilder(schema.getField("rec").schema).set("x", 7).build())
      .build()

    val actual = AvroDecoder(Map(1 -> schema).get).decode(AvroFixtures.encode(1, record))
    val expected = parse("""
      {"n": null, "b": true, "i": 42, "l": 1099511627776, "f": 1.5, "d": 2.25, "nan": null,
       "s": "héllo", "by": "AQID", "fx": "/wA=", "e": "GREEN", "a": [1, 2], "m": {"k": "v"},
       "u1": null, "u2": "x", "u3": "y", "dec": 123.45, "ts": 1700000000000, "date": 19000,
       "uuid": "6f1c9c1e-1b1a-4c4e-9a7e-2f1f0e2d3c4b", "rec": {"x": 7}}""").toOption.get
    assertEquals(actual, expected)
    // field order is the schema order
    assertEquals(actual.asObject.get.keys.toList, expected.asObject.get.keys.toList)
  }

  test("decoding errors") {
    val decoder = AvroDecoder(Map(1 -> schema).get)
    intercept[DecodeError](decoder.decode(Array[Byte](1, 0, 0, 0, 1, 0)))  // bad magic byte
    intercept[DecodeError](decoder.decode(Array[Byte](0, 0, 0)))           // too short
    intercept[DecodeError](decoder.decode(Confluent.frame(2, Array[Byte](0)))) // unknown schema
    intercept[DecodeError](decoder.decode(Confluent.frame(1, Array[Byte](1)))) // truncated
  }

  test("schema ids of a batch") {
    val payloads = List(Confluent.frame(1, Array()), Confluent.frame(3, Array(1)), "{}".getBytes, null, Confluent.frame(1, Array()))
    assertEquals(Confluent.schemaIds(payloads), Set(1, 3))
  }
