package konsume.bench

import io.circe.parser.parse

import java.nio.file.{Files, Path}

/** Compares two results of `bench/run.sh` (JMH's JSON), program by program: time per batch and
  * bytes allocated per record. Exits with 1 if anything regressed:
  *   - time: slower by more than 10 % and by more than both error margins together;
  *   - allocations: one byte per record more (they are stable to the byte).
  *
  * sbt 'bench/runMain konsume.bench.Compare before.json after.json' (or bench/compare.sh)
  */
object Compare:

  /** The records of a batch: allocations are reported per record. */
  private val Records = BenchData.events.size.toDouble

  final case class Result(time: Double, error: Double, bytesPerRecord: Double)

  def main(args: Array[String]): Unit =
    val (before, after) = (load(args(0)), load(args(1)))
    println(s"before: ${Path.of(args(0)).getFileName}\nafter:  ${Path.of(args(1)).getFileName}\n")
    println(f"${"program"}%-14s ${"format"}%-6s ${"engine"}%-11s ${"time (µs per batch)"}%-30s ${"bytes per record"}%-22s")
    var regressions = 0
    for key <- (before.keySet ++ after.keySet).toList.sorted do
      val (program, format, engine) = key
      (before.get(key), after.get(key)) match
        case (Some(b), Some(a)) =>
          val dt       = (a.time - b.time) / b.time * 100
          val beyond   = math.abs(a.time - b.time) > b.error + a.error && math.abs(dt) > 10
          val timeMark = if beyond && dt > 0 then "  SLOWER" else if beyond then "  faster" else ""
          val db       = a.bytesPerRecord - b.bytesPerRecord
          val allocMark = if db >= 1 then "  MORE" else if db <= -1 then "  less" else ""
          if timeMark == "  SLOWER" || allocMark == "  MORE" then regressions += 1
          val time  = f"${b.time}%.0f → ${a.time}%.0f (${dt}%+.0f%%)$timeMark"
          val bytes = f"${b.bytesPerRecord}%.1f → ${a.bytesPerRecord}%.1f$allocMark"
          println(f"$program%-14s $format%-6s $engine%-11s $time%-30s $bytes%-22s")
        case (b, _) =>
          println(f"$program%-14s $format%-6s $engine%-11s ${if b.isEmpty then "new" else "removed"}")
    println()
    if regressions > 0 then
      println(s"$regressions regression(s)")
      sys.exit(1)
    else println("no regression")

  private def load(file: String): Map[(String, String, String), Result] =
    val json = parse(Files.readString(Path.of(file))).fold(e => sys.error(s"$file: $e"), identity)
    json.asArray.getOrElse(Vector.empty).flatMap { run =>
      val c = run.hcursor
      for
        params  <- c.downField("params").as[Map[String, String]].toOption
        primary  = c.downField("primaryMetric")
        time    <- primary.downField("score").as[Double].toOption
        error    = primary.downField("scoreError").as[Double].toOption.filterNot(_.isNaN).getOrElse(0.0)
        alloc    = c.downField("secondaryMetrics").downField("gc.alloc.rate.norm").downField("score").as[Double].getOrElse(Double.NaN)
      yield (params.getOrElse("program", "?"), params.getOrElse("format", "?"), params.getOrElse("engine", "?")) -> Result(time, error, alloc / Records)
    }.toMap
