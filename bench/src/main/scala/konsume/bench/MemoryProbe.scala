package konsume.bench

import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline}

import java.lang.management.{ManagementFactory, MemoryType}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** Memory kept by an engine, in a fresh JVM: distinct jq programs on the Avro bench schema, each
  * pipeline kept alive (as by open consumers) and run once on a few records so that its program
  * is built. Reports heap, metaspace and class counts after a full GC, then after dropping them.
  *
  * sbt 'set bench/run/fork := true' 'bench/runMain konsume.bench.MemoryProbe Planned 1000'
  */
object MemoryProbe:

  def main(args: Array[String]): Unit =
    val engine = Engine.valueOf(args(0))
    val total  = args.lift(1).fold(1000)(_.toInt)
    val batch  = BenchData.avroBytes.take(100)
    val alive  = ArrayBuffer.empty[AnyRef]
    report(engine, "baseline", 0)
    var built = 0
    for step <- List(1, 10, 100, 1000, 10000).filter(_ <= total) do
      while built < step do
        val p = Pipeline
          .forBytes[String](
            ConsumeConfig(Seq("topic"), PayloadFormat.Avro, Some(CompilationCurveBench.source(built)), engine),
            Map(BenchData.SchemaId -> BenchData.schema).get
          )
          .fold(throw _, identity)
        p(batch) // builds the program for the schema id
        alive += p
        built += 1
      report(engine, "alive", alive.size)
    alive.clear()
    report(engine, "dropped", 0)
    if sys.props.contains("probe.histo") then
      // in-process: no attach needed
      val server = ManagementFactory.getPlatformMBeanServer
      val name   = javax.management.ObjectName("com.sun.management:type=DiagnosticCommand")
      val histo = server
        .invoke(name, "gcClassHistogram", Array[AnyRef](Array.empty[String]), Array(classOf[Array[String]].getName))
        .asInstanceOf[String]
      histo.linesIterator.take(30).foreach(l => println(s"HISTO $l"))

  private def report(engine: Engine, label: String, alive: Int): Unit =
    for _ <- 1 to 3 do System.gc()
    val heap      = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
    val metaspace = ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(p => p.getType == MemoryType.NON_HEAP && p.getName == "Metaspace")
      .map(_.getUsage.getUsed)
      .sum
    val classes = ManagementFactory.getClassLoadingMXBean
    def mib(b: Long) = b / 1048576.0
    println(
      f"PROBE $engine%-11s $label%-8s alive=$alive%5d heap=${mib(heap)}%7.1f MiB metaspace=${mib(metaspace)}%6.1f MiB " +
        f"loaded=${classes.getLoadedClassCount}%6d unloaded=${classes.getUnloadedClassCount}%6d"
    )
