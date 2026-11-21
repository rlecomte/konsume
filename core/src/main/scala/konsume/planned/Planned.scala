package konsume.planned

import konsume.Record
import konsume.jq.{Jq, JqError}

import scala.collection.mutable.ArrayBuffer

/** The planned engine: runs a jq program on the bytes of a record without decoding it.
  *
  *  1. the paths the program uses are found on its syntax tree ([[Demand]]);
  *  2. a reader, one per format, fills the slots of a [[Frame]] with what it finds at these paths,
  *     and describes each value ([[PVal]]): see `avro.AvroPlanned` and `json.JsonPlanned`;
  *  3. the program is turned into closures once ([[Program]]), specialised on these values.
  */
object Planned:

  type Fn = (Record[Any, Array[Byte]], ArrayBuffer[Record[Any, Array[Byte]]]) => Unit

  /** The engine for one program: `read` fills the slots of a frame from the record's bytes,
    * then the program runs on them. One frame per thread, reused from record to record: running
    * allocates nothing of its own.
    */
  def build[F <: Frame](program: Jq, penv: Map[Path, PVal], newFrame: Int => F)(read: F => Unit): Fn =
    val p      = Program(penv)
    val run    = p.node(program, Some(Nil), p.emit)
    val regs   = p.registers
    val frames = ThreadLocal.withInitial(() => newFrame(regs))
    (record, out) =>
      val f = frames.get()
      f.begin(record, out)
      try
        read(f)
        run(f, Root)
      catch case _: JqError => () // the record is skipped, outputs already emitted are kept
      finally f.clear()            // the frame outlives the batch: keep no record nor output alive
