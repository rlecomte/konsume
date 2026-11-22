package buildyourown

import scala.util.Random

import Schema.*
import Value.*

/** The planned engine against the interpreter, on random schemas, records and programs: for
  * every program the planned engine handles, the very same outputs, on every record.
  */
class OracleSuite extends munit.FunSuite:

  private val Schemas  = 200
  private val Programs = 50
  private val Records  = 20

  test("random schemas, records and programs: the planned engine gives what the interpreter gives") {
    val rnd                 = Random(7) // fixed: a failure can be run again
    var (handled, fallback) = (0, 0)
    for _ <- 1 to Schemas do
      val gen     = Gen(rnd)
      val schema  = gen.schema()
      val records = List.fill(Records)(gen.value(schema))
      for _ <- 1 to Programs do
        val source  = gen.program(schema)
        val program = Parser.parse(source)
        Planned.compile(program, schema) match
          case None => fallback += 1
          case Some(engine) =>
            handled += 1
            for r <- records do
              val bytes = Binary.encode(r, schema)
              val clue  = s"\nprogram: $source\nschema:  $schema\nrecord:  ${r.render}"
              // a crash of the planned engine is a failure too: with the case that caused it
              val actual = try engine.run(bytes) catch case e: Exception => fail(s"$e$clue")
              assertEquals(actual, Reference.run(program, schema, bytes), clue)
    // the oracle only checks what the planned engine runs: make sure it runs enough of them
    println(s"planned engine: $handled programs, interpreter: $fallback")
    assert(handled > fallback, s"handled $handled, fallback $fallback")
  }

  /** Values close to one another (small ints, few strings) so that `==` is sometimes true, and
    * field names from a small pool so that paths often exist, and sometimes not.
    */
  private final class Gen(rnd: Random):
    private val names = Vector("a", "b", "c")
    private def pick[T](xs: T*): T = xs(rnd.nextInt(xs.size))

    def schema(depth: Int = 0): SRecord =
      SRecord(rnd.shuffle(names).take(1 + rnd.nextInt(3)).map(n => n -> tpe(depth + 1)).toList)

    private def tpe(depth: Int): Schema = rnd.nextInt(if depth >= 3 then 2 else 3) match
      case 0 => SInt
      case 1 => SString
      case _ => schema(depth)

    def value(s: Schema): Value = s match
      case SInt            => Num(rnd.nextInt(5) - 2)
      case SString         => Str(pick("", "x", "y", "xy", "\"", "\\"))
      case SRecord(fields) => Obj(fields.map((n, t) => n -> value(t)))

    /** Mostly a path down to an int or a string of the schema; sometimes any path (missing,
      * through a string, or ending on a record, which the planned engine leaves to the interpreter).
      */
    private def path(schema: Schema): String =
      def down(s: Schema): String = s match
        case SRecord(fields) if fields.nonEmpty =>
          val (name, t) = fields(rnd.nextInt(fields.size))
          s".$name${down(t)}"
        case _ => ""
      if rnd.nextInt(4) > 0 then down(schema)
      else pick(".", (1 to 1 + rnd.nextInt(3)).map(_ => "." + names(rnd.nextInt(3))).mkString)
    private def literal(): String               = pick("0", "1", "-1", "2", "\"x\"", "\"\"", "\"xy\"")
    private def operand(schema: Schema): String = if rnd.nextInt(3) == 0 then literal() else path(schema)
    private def cond(schema: Schema): String    = s"${operand(schema)} ${pick("==", ">")} ${operand(schema)}"

    /** One to three stages: a select, a path; now and then a condition emitted (left to the
      * interpreter, as the paths that turn out to be records).
      */
    def program(schema: Schema): String =
      val stages = List.fill(1 + rnd.nextInt(3))(rnd.nextInt(10) match
        case 0     => cond(schema)
        case 1 | 2 => path(schema)
        case _     => s"select(${cond(schema)})"
      )
      stages.mkString(" | ")
