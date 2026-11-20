package konsume.bench

import konsume.format.JsonStaged
import konsume.jq.Parser

import java.lang.management.{ManagementFactory, MemoryType}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** Compiles distinct jq programs through `JsonStaged.compile`, as an interactive user would, and
  * reports metaspace and class counts after a full GC, while the compiled programs are kept
  * alive (as by running pipelines). Then drops them and reports again, to show what they alone
  * keep alive.
  *
  * sbt 'set bench/run/fork := true' 'bench/runMain konsume.bench.MetaspaceProbe 2000'
  */
object MetaspaceProbe:

  def main(args: Array[String]): Unit =
    val total = args.headOption.fold(2000)(_.toInt)
    report("start", 0)
    val alive    = ArrayBuffer.empty[AnyRef]
    var compiled = 0
    for step <- Iterator.iterate(250)(_ * 2).takeWhile(_ <= total).toList :+ total if step > compiled do
      while compiled < step do
        alive += JsonStaged.compile(Parser.parse(CompilationCurveBench.source(compiled)).fold(throw _, identity))
        compiled += 1
      report("alive", compiled, alive.size)

    alive.clear()
    report("dropped", compiled, alive.size)

  private def report(label: String, compiled: Int, aliveCount: Int = 0): Unit =
    for _ <- 1 to 3 do System.gc()
    val classes   = ManagementFactory.getClassLoadingMXBean
    val metaspace = ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(p => p.getType == MemoryType.NON_HEAP && p.getName == "Metaspace")
      .map(_.getUsage.getUsed)
      .sum
    println(
      f"$label%-14s compiled=$compiled%5d alive=$aliveCount%5d metaspace=${metaspace / 1048576.0}%7.1f MiB " +
        f"loaded=${classes.getLoadedClassCount}%6d unloaded=${classes.getUnloadedClassCount}%6d"
    )
