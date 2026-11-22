package buildyourown

/** An output of a program on a record. */
enum Output:
  /** the record itself, unchanged: emitted with its own bytes */
  case Original
  /** any other value, written as JSON */
  case Text(json: String)

/** The reference engine: decode the whole record, run the interpreter on it. */
object Reference:

  def run(program: Expr, schema: Schema, bytes: Array[Byte]): List[Output] =
    val record = Binary.decode(bytes, schema)
    Interpreter.eval(program, record).map(v => if v eq record then Output.Original else Output.Text(v.render))
