package buildyourown

/** The values a program works on: what a record decodes to, what a comparison gives. */
enum Value:
  case Null
  case Bool(b: Boolean)
  case Num(n: Long)
  case Str(s: String)
  case Obj(fields: List[(String, Value)])

  /** The value as JSON text: how an output is written. */
  def render: String = this match
    case Null    => "null"
    case Bool(b) => b.toString
    case Num(n)  => n.toString
    case Str(s)  => Value.quote(s)
    case Obj(fs) => fs.map((k, v) => s"${Value.quote(k)}:${v.render}").mkString("{", ",", "}")

object Value:
  /** A JSON string: quotes and backslashes escaped (the small konsume's strings are plain ASCII). */
  def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
