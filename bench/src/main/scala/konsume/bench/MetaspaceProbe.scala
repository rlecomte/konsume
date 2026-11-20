package konsume.bench

import konsume.format.JsonStaged
import konsume.jq.Parser

import java.lang.management.{ManagementFactory, MemoryType}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Compiles distinct jq programs through `JsonStaged.compile`, as an interactive user would, and
  * reports metaspace and class counts after a full GC. Then empties the compiled-program cache
  * and reports again, to show what the cache alone keeps alive.
  *
  * sbt 'set bench/run/fork := true' 'bench/runMain konsume.bench.MetaspaceProbe 2000'
  */
object MetaspaceProbe:

  def main(args: Array[String]): Unit =
    val total = args.headOption.fold(2000)(_.toInt)
    report("start", 0)
    var compiled = 0
    for step <- Iterator.iterate(250)(_ * 2).takeWhile(_ <= total).toList :+ total if step > compiled do
      while compiled < step do
        JsonStaged.compile(Parser.parse(CompilationCurveBench.source(compiled)).fold(throw _, identity))
        compiled += 1
      report("cached", compiled)

    cache.clear()
    report("cache cleared", compiled)

  private def cache: ConcurrentHashMap[?, ?] =
    val field = JsonStaged.getClass.getDeclaredField("cache")
    field.setAccessible(true)
    field.get(JsonStaged).asInstanceOf[ConcurrentHashMap[?, ?]]

  private def report(label: String, compiled: Int): Unit =
    for _ <- 1 to 3 do System.gc()
    val classes   = ManagementFactory.getClassLoadingMXBean
    val metaspace = ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(p => p.getType == MemoryType.NON_HEAP && p.getName == "Metaspace")
      .map(_.getUsage.getUsed)
      .sum
    println(
      f"$label%-14s compiled=$compiled%5d cacheSize=${cache.size}%5d metaspace=${metaspace / 1048576.0}%7.1f MiB " +
        f"loaded=${classes.getLoadedClassCount}%6d unloaded=${classes.getUnloadedClassCount}%6d"
    )
