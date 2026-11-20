package konsume.bench

import io.circe.Json
import konsume.format.{AvroDecoder, Confluent, Decoder, JsonDecoder}
import konsume.jq.Parser
import konsume.{ConsumeConfig, PayloadFormat, Pipeline, Record}
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DecoderFactory
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Where does the time go, from Kafka bytes to filtered records?
  * `select(.user.age > 30 and .user.country == "FR")` on 10k records.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
class DecodeBench:

  @Param(Array("json", "avro"))
  var format: String = scala.compiletime.uninitialized

  private val program = """select(.user.age > 30 and .user.country == "FR")"""

  private var bytes: Vector[Record[String, Array[Byte]]]                       = scala.compiletime.uninitialized
  private var decoder: Decoder                                                 = scala.compiletime.uninitialized
  private var full: Pipeline[String, Array[Byte], String, Array[Byte]]         = scala.compiletime.uninitialized
  private var reference: Pipeline[String, Array[Byte], String, Array[Byte]]    = scala.compiletime.uninitialized
  private var filterOnly: Pipeline[String, Json, String, Json]                 = scala.compiletime.uninitialized
  private val avroReader = GenericDatumReader[Any](BenchData.schema)

  @Setup
  def setup(): Unit =
    val payloadFormat = if format == "json" then PayloadFormat.Json else PayloadFormat.Avro
    bytes = if format == "json" then BenchData.jsonBytes else BenchData.avroBytes
    decoder = if format == "json" then JsonDecoder else AvroDecoder(Map(BenchData.SchemaId -> BenchData.schema).get)
    full = Pipeline
      .forBytes[String](ConsumeConfig(Seq("topic"), payloadFormat, Some(program)), Map(BenchData.SchemaId -> BenchData.schema).get)
      .fold(throw _, identity)
    filterOnly = Pipeline.interpreted(Parser.parse(program).fold(throw _, identity))
    reference = Pipeline.decodeThen(decoder, filterOnly)
    // a fast pipeline that drops everything would be a fine benchmark of nothing
    val (got, expected) = (full(bytes).map(_.offset).toVector, reference(bytes).map(_.offset).toVector)
    require(got == expected, s"$format: the pipelines disagree")
    require(expected.size > 100, s"$format: only ${expected.size} records kept")
    System.err.println(s"$format: ${expected.size} records kept out of ${bytes.size}, same as the reference")
    val avg = bytes.map(_.value.length).sum.toDouble / bytes.size
    System.err.println(f"$format: $avg%.1f bytes per payload on average")

  /** bytes => filtered records, as the library does it (Avro: staged decoder). */
  @Benchmark def bytesToFiltered(bh: Blackhole): Unit = bh.consume(full(bytes))

  /** bytes => filtered records, decoding to JSON first (circe / Apache Avro), then interpreted filter. */
  @Benchmark def bytesToFilteredViaJson(bh: Blackhole): Unit = bh.consume(reference(bytes))

  /** bytes => JSON view, nothing else. */
  @Benchmark def decodeToJson(bh: Blackhole): Unit =
    val it = bytes.iterator
    while it.hasNext do bh.consume(decoder.decode(it.next().value))

  /** Only the interpreted filter, on values decoded beforehand. */
  @Benchmark def filterDecoded(bh: Blackhole): Unit = bh.consume(filterOnly(BenchData.jsonValues))

  /** Avro only: bytes => GenericRecord, without the conversion to JSON. */
  @Benchmark def avroGenericRecord(bh: Blackhole): Unit =
    if format == "avro" then
      val it = bytes.iterator
      while it.hasNext do
        val b = it.next().value
        bh.consume(avroReader.read(null, DecoderFactory.get.binaryDecoder(b, Confluent.HeaderSize, b.length - Confluent.HeaderSize, null)))
