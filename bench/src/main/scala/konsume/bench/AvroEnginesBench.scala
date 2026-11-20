package konsume.bench

import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** PROTOTYPE comparison: 10k Avro payloads through the same jq program, with the three engines.
  * Staged generates bytecode; Planned runs the same reading plan with an interpreter and the
  * program as closures; Interpreted decodes everything (Apache Avro => JSON) then interprets.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
class AvroEnginesBench:

  @Param(Array("filter", "regex", "project", "iterate"))
  var program: String = scala.compiletime.uninitialized

  @Param(Array("Staged", "Planned", "Interpreted"))
  var engine: String = scala.compiletime.uninitialized

  private var batch: Vector[Record[String, Array[Byte]]]                    = scala.compiletime.uninitialized
  private var pipeline: Pipeline[String, Array[Byte], String, Array[Byte]] = scala.compiletime.uninitialized

  @Setup
  def setup(): Unit =
    batch = BenchData.avroBytes
    val source = program match
      case "filter"  => """select(.user.age > 30 and .user.country == "FR")"""
      case "regex"   => """select(.name | test("^jo"))"""
      case "project" => ".user.country"
      case "iterate" => ".items[] | select(. > 50)"
    pipeline = Pipeline
      .forBytes[String](
        ConsumeConfig(Seq("topic"), PayloadFormat.Avro, Some(source), Engine.valueOf(engine)),
        Map(BenchData.SchemaId -> BenchData.schema).get
      )
      .fold(throw _, identity)

  @Benchmark def run(bh: Blackhole): Unit = bh.consume(pipeline(batch))
