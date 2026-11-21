package konsume.bench

import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** One program per shape of jq program, on the same 10k events, Avro and JSON, each engine: what
  * each shape costs, in time and allocations (`-prof gc`), on the planned engine's fast paths
  * (slots) and generic ones (through `Json`).
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class ShapesBench:

  @Param(
    Array(
      "filter",        // comparisons of paths with literals
      "regex",         // test() with a literal regex
      "prefix",        // startswith() with a literal
      "compare2",      // comparison between two paths
      "length",        // length of an array
      "project",       // a string field
      "projectNum",    // a number field
      "projectObj",    // a nested record
      "filterProject", // select then a field
      "index",         // .items[0]
      "iterate"        // .items[] | select(...)
    )
  )
  var program: String = scala.compiletime.uninitialized

  @Param(Array("Avro", "Json"))
  var format: String = scala.compiletime.uninitialized

  @Param(Array("Planned", "Interpreted"))
  var engine: String = scala.compiletime.uninitialized

  private var batch: Vector[Record[String, Array[Byte]]]                    = scala.compiletime.uninitialized
  private var pipeline: Pipeline[String, Array[Byte], String, Array[Byte]] = scala.compiletime.uninitialized

  @Setup
  def setup(): Unit =
    val payloadFormat = PayloadFormat.valueOf(format)
    batch = if payloadFormat == PayloadFormat.Avro then BenchData.avroBytes else BenchData.jsonBytes
    val source = ShapesBench.programs(program)
    pipeline = Pipeline
      .forBytes[String](
        ConsumeConfig(Seq("topic"), payloadFormat, Some(source), Engine.valueOf(engine)),
        Map(BenchData.SchemaId -> BenchData.schema).get
      )
      .fold(throw _, identity)

  @Benchmark def run(bh: Blackhole): Unit = bh.consume(pipeline(batch))

object ShapesBench:
  val programs: Map[String, String] = Map(
    "filter"        -> """select(.user.age > 30 and .user.country == "FR")""",
    "regex"         -> """select(.name | test("^jo"))""",
    "prefix"        -> """select(.name | startswith("jo"))""",
    "compare2"      -> """select(.name == .user.country)""",
    "length"        -> """select(.items | length > 2)""",
    "project"       -> ".user.country",
    "projectNum"    -> ".user.age",
    "projectObj"    -> ".user",
    "filterProject" -> """select(.user.age > 30) | .name""",
    "index"         -> ".items[0]",
    "iterate"       -> ".items[] | select(. > 50)"
  )
