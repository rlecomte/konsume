package konsume.format

import io.circe.Json
import io.circe.parser.parse
import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecordBuilder

import java.nio.charset.StandardCharsets.UTF_8

class BytesPipelineSuite extends munit.FunSuite:

  private val v1 = AvroFixtures.schema("""
    {"type": "record", "name": "Event", "fields": [
      {"name": "name", "type": "string"},
      {"name": "user", "type": {"type": "record", "name": "User", "fields": [
        {"name": "age",     "type": "int"},
        {"name": "country", "type": ["null", "string"]}
      ]}}
    ]}""")

  // v2 adds a field with a default, as a compatible evolution would
  private val v2 = AvroFixtures.schema("""
    {"type": "record", "name": "Event", "fields": [
      {"name": "name", "type": "string"},
      {"name": "user", "type": {"type": "record", "name": "User", "fields": [
        {"name": "age",     "type": "int"},
        {"name": "country", "type": ["null", "string"]}
      ]}},
      {"name": "vip", "type": "boolean", "default": false}
    ]}""")

  private val schemas: Map[Int, Schema] = Map(1 -> v1, 2 -> v2)

  private def event(schema: Schema, name: String, age: Int, country: String | Null, vip: Boolean = false) =
    val user = GenericRecordBuilder(schema.getField("user").schema).set("age", age).set("country", country).build()
    val b    = GenericRecordBuilder(schema).set("name", name).set("user", user)
    if schema.getField("vip") != null then b.set("vip", vip)
    b.build()

  private def records(values: Array[Byte]*): Vector[Record[String, Array[Byte]]] =
    values.zipWithIndex.map((v, i) => Record("t", 0, i.toLong, 0L, s"k$i", v)).toVector

  private def run(format: PayloadFormat, program: String, engine: Engine, batch: Vector[Record[String, Array[Byte]]]) =
    Pipeline
      .forBytes[String](ConsumeConfig(Seq("t"), format, Some(program), engine), schemas.get)
      .fold(throw _, identity)(batch)
      .toVector

  private def json(bytes: Array[Byte]): Json = parse(String(bytes, UTF_8)).fold(throw _, identity)

  private val avroBatch = records(
    AvroFixtures.encode(1, event(v1, "john", 42, "FR")),
    AvroFixtures.encode(1, event(v1, "jane", 17, null)),
    "not avro".getBytes(UTF_8),
    Confluent.frame(99, Array[Byte](0)),       // unknown schema id
    AvroFixtures.encode(2, event(v2, "joe", 35, "FR", vip = true)),
    AvroFixtures.encode(2, event(v2, "bob", 50, "US"))
  )

  private val jsonBatch = records(
    """{"name":"john","user":{"age":42,"country":"FR"}}""".getBytes(UTF_8),
    """{"name":"jane","user":{"age":17,"country":null}}""".getBytes(UTF_8),
    """{"name": oops""".getBytes(UTF_8),
    """{"name":"joe","user":{"age":35,"country":"FR"},"vip":true}""".getBytes(UTF_8)
  )

  for engine <- Engine.values do
    test(s"$engine: an Avro filter emits the original bytes, untouched") {
      val out = run(PayloadFormat.Avro, """select(.user.country == "FR")""", engine, avroBatch)
      assertEquals(out.map(_.offset), Vector(0L, 4L))
      out.foreach(r => assert(avroBatch.exists(_.value eq r.value)))
    }

    test(s"$engine: an Avro transformation emits JSON") {
      val out = run(PayloadFormat.Avro, ".user | select(.age > 18)", engine, avroBatch)
      assertEquals(out.map(_.offset), Vector(0L, 4L, 5L))
      assertEquals(json(out.head.value), parse("""{"age":42,"country":"FR"}""").toOption.get)
    }

    test(s"$engine: Avro schema evolution, a field missing from the old schema is null") {
      val out = run(PayloadFormat.Avro, "select(.vip == null) | .name", engine, avroBatch)
      assertEquals(out.map(r => json(r.value)), Vector(Json.fromString("john"), Json.fromString("jane")))
    }

    test(s"$engine: JSON filter and transformation, malformed values skipped") {
      val kept = run(PayloadFormat.Json, "select(.user.age > 18)", engine, jsonBatch)
      assertEquals(kept.map(_.offset), Vector(0L, 3L))
      kept.foreach(r => assert(jsonBatch.exists(_.value eq r.value)))
      val names = run(PayloadFormat.Json, ".name", engine, jsonBatch)
      assertEquals(names.map(r => json(r.value)), Vector("john", "jane", "joe").map(Json.fromString))
    }
