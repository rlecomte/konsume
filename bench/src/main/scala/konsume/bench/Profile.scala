package konsume.bench

import jdk.jfr.consumer.{RecordedEvent, RecordedFrame, RecordingFile}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** What a JFR recording of `bench/profile.sh` says, on the samples taken in konsume, circe or
  * jawn code (not JMH's): the methods the CPU is in (self time), the methods under which it is
  * (total time, konsume's only), and what allocates (sampled, weighted by bytes).
  *
  * sbt 'bench/runMain konsume.bench.Profile recording.jfr'
  */
object Profile:

  def main(args: Array[String]): Unit =
    val events = RecordingFile.readAllEvents(Path.of(args(0))).asScala.toVector
    cpu(events.filter(_.getEventType.getName == "jdk.ExecutionSample"))
    allocations(events.filter(_.getEventType.getName == "jdk.ObjectAllocationSample"))

  private def frames(e: RecordedEvent): Vector[RecordedFrame] =
    Option(e.getStackTrace).fold(Vector.empty)(_.getFrames.asScala.toVector)

  private def ours(fs: Vector[RecordedFrame]): Boolean =
    fs.exists { f =>
      val t = f.getMethod.getType.getName
      t.startsWith("konsume.") && !t.startsWith("konsume.bench.") || t.startsWith("io.circe") || t.startsWith("org.typelevel.jawn")
    }

  private def name(f: RecordedFrame): String =
    s"${f.getMethod.getType.getName.stripPrefix("konsume.")}.${f.getMethod.getName}"

  private def cpu(samples: Vector[RecordedEvent]): Unit =
    val stacks = samples.map(frames).filter(ours)
    val n      = stacks.size.max(1).toDouble
    println(s"CPU: ${stacks.size} samples")
    println("  self:")
    top(stacks.map(fs => name(fs.head)), n, 15)
    println("  total (konsume methods):")
    // a konsume method lost its "konsume." prefix in `name`; the others keep their package
    val library = List("java.", "jdk.", "scala.", "io.", "org.", "bench.")
    top(stacks.flatMap(fs => fs.map(name).distinct.filterNot(m => library.exists(m.startsWith))), n, 15)

  private def allocations(samples: Vector[RecordedEvent]): Unit =
    val ofOurs = samples.filter(e => ours(frames(e)))
    val total  = ofOurs.map(_.getLong("weight")).sum.max(1L).toDouble
    println(s"\nAllocations: ${ofOurs.size} samples")
    ofOurs
      .groupMapReduce { e =>
        s"${e.getClass("objectClass").getName}  ${frames(e).take(4).map(name).mkString(" < ")}"
      }(_.getLong("weight"))(_ + _)
      .toVector.sortBy(-_._2).take(15)
      .foreach((k, w) => println(f"  ${100 * w / total}%5.1f%% $k"))

  private def top(names: Vector[String], n: Double, k: Int): Unit =
    names.groupMapReduce(identity)(_ => 1)(_ + _).toVector.sortBy(-_._2).take(k)
      .foreach((m, c) => println(f"    ${100 * c / n}%5.1f%% $m"))
