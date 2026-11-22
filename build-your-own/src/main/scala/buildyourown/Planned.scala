package buildyourown

import java.nio.charset.StandardCharsets.UTF_8

import PVal.*

/** The planned engine: for one program and one schema, the demand, the reading plan and the
  * program as closures are built once; each record is then read into the slots and run through
  * the conditions. `None` when the program is not one it handles: use [[Reference]].
  */
object Planned:

  def compile(program: Expr, schema: Schema): Option[Engine] =
    val plan = Plan(schema, Demand.of(program))
    Program(plan.values).compile(program).map(Engine(plan, _))

  final class Engine(plan: Plan, program: Compiled):
    // one frame, reused for every record (one engine per thread)
    private val frame = Frame(plan.slots)
    private val conds = program.conds.toArray
    private val output: Frame => Output =
      if program.output.isEmpty then _ => Output.Original // the record itself: its own bytes
      else
        val text = write(plan.values(program.output))
        f => Output.Text(text(f))

    def run(bytes: Array[Byte]): List[Output] =
      frame.bytes = bytes
      plan.read(frame)
      var i = 0
      while i < conds.length do
        if !conds(i)(frame) then return Nil
        i += 1
      List(output(frame))

  /** The JSON text of a path's value, from its slots: the text `Value.render` gives. */
  private def write(v: PVal): Frame => String = v match
    case PInt(s)        => f => f.nums(s).toString
    case PStr(off, len) => f => Value.quote(String(f.bytes, f.nums(off).toInt, f.nums(len).toInt, UTF_8))
    case PNull          => _ => "null"
