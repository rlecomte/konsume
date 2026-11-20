package konsume.bench

import konsume.format.{AvroStaged, JsonStaged}
import konsume.jq.{Jq, Parser}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** How long the N-th staged compilation takes after the JVM starts, as when users submit
  * their own filters: every invocation compiles a program never seen before, so the caches of
  * the compiled programs never hit. Each measured iteration is one compilation, there is no
  * warmup: iteration N of a fork is the N-th compilation of a fresh JVM.
  *
  * Summarize with `bench/scripts/compilation_curve.py`.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 200, batchSize = 1)
@Fork(5)
class CompilationCurveBench:

  @Param(Array("json", "avro"))
  var format: String = scala.compiletime.uninitialized

  private var programs: Array[Jq] = scala.compiletime.uninitialized
  private var next                = 0

  @Setup(Level.Trial)
  def setup(): Unit =
    programs = Array.tabulate(200)(i => Parser.parse(CompilationCurveBench.source(i)).fold(throw _, identity))

  @Benchmark
  def compile(): AnyRef =
    val program = programs(next)
    next += 1
    if format == "json" then JsonStaged.compile(program) else AvroStaged.compile(program, BenchData.schema)

object CompilationCurveBench:
  /** Different shapes, and a different literal in each, so that no two programs are equal. */
  def source(i: Int): String = i % 5 match
    case 0 => s"select(.user.age > $i)"
    case 1 => s"""select(.user.age > $i and .user.country == "FR")"""
    case 2 => s"""select(.name | test("^jo$i"))"""
    case 3 => s".items[] | select(. > $i)"
    case _ => s"""select(.user.country == "C$i" or .user.age < $i) | .user"""
