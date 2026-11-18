package konsume.bench

import io.circe.Json
import konsume.jq.Parser
import konsume.{Pipeline, Record}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer

/** One batch of 10k already-deserialised records through the same jq program, three ways:
  * written by hand at compile time, interpreted, staged.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
class JqBench:

  @Param(Array("filter", "regex", "iterate"))
  var program: String = scala.compiletime.uninitialized

  private var batch: Vector[Record[String, Json]]                        = scala.compiletime.uninitialized
  private var staged: Pipeline[String, Json, String, Json]               = scala.compiletime.uninitialized
  private var interpreted: Pipeline[String, Json, String, Json]          = scala.compiletime.uninitialized
  private var handwritten: Iterable[Record[String, Json]] => Iterable[Record[String, Json]] =
    scala.compiletime.uninitialized

  @Setup
  def setup(): Unit =
    batch = BenchData.jsonValues
    val source = program match
      case "filter"  => """select(.user.age > 30 and .user.country == "FR")"""
      case "regex"   => """select(.name | test("^jo"))"""
      case "iterate" => ".items[] | select(. > 50)"
    val ast = Parser.parse(source).fold(throw _, identity)
    staged = Pipeline.staged(ast)
    interpreted = Pipeline.interpreted(ast)
    handwritten = program match
      case "filter"  => Handwritten.filter
      case "regex"   => Handwritten.regex
      case "iterate" => Handwritten.iterate

  @Benchmark def handwrittenScala(bh: Blackhole): Unit = bh.consume(handwritten(batch))
  @Benchmark def stagedJq(bh: Blackhole): Unit         = bh.consume(staged(batch))
  @Benchmark def interpretedJq(bh: Blackhole): Unit    = bh.consume(interpreted(batch))

/** What a developer would write at compile time for each program, with circe, in the same
  * loop-over-a-batch shape as the generated code so that only the jq part is compared.
  */
object Handwritten:
  type F = Iterable[Record[String, Json]] => Iterable[Record[String, Json]]

  private inline def loop(batch: Iterable[Record[String, Json]])(
      inline body: (Record[String, Json], ArrayBuffer[Record[String, Json]]) => Unit
  ): ArrayBuffer[Record[String, Json]] =
    val out = ArrayBuffer.empty[Record[String, Json]]
    val it  = batch.iterator
    while it.hasNext do body(it.next(), out)
    out

  val filter: F = batch =>
    loop(batch) { (r, out) =>
      val keep = r.value.asObject.flatMap(_("user")).flatMap(_.asObject).exists { user =>
        user("age").flatMap(_.asNumber).exists(_.toDouble > 30) &&
        user("country").flatMap(_.asString).contains("FR")
      }
      if keep then out += r
    }

  private val jo = Pattern.compile("^jo")
  val regex: F = batch =>
    loop(batch) { (r, out) =>
      if r.value.asObject.flatMap(_("name")).flatMap(_.asString).exists(jo.matcher(_).find()) then out += r
    }

  val iterate: F = batch =>
    loop(batch) { (r, out) =>
      r.value.asObject.flatMap(_("items")).flatMap(_.asArray).foreach { items =>
        items.foreach(j => if j.asNumber.exists(_.toDouble > 50) then out += r.copy(value = j))
      }
    }
