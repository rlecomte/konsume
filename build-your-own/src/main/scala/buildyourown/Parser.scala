package buildyourown

final class ParseError(message: String) extends Exception(message)

/** Text to [[Expr]], by recursive descent: one method per level of the grammar, the loosest
  * first. Each one parses what it can, then hands the rest to the next level.
  *
  * {{{
  * pipe    := compare ("|" pipe)?        right-associative: a | b | c is a | (b | c)
  * compare := term (("==" | ">") term)?
  * term    := path | number | string | "select" "(" pipe ")"
  * path    := "." name? ("." name)*      ".", ".a", ".a.b"
  * }}}
  */
object Parser:
  def parse(source: String): Expr =
    val p = Parser(source)
    val e = p.pipe()
    p.end()
    e

private final class Parser(source: String):
  private var pos = 0

  def pipe(): Expr =
    val left = compare()
    if accept("|") then Expr.Pipe(left, pipe()) else left

  def compare(): Expr =
    val left = term()
    if accept("==") then Expr.Compare(Op.Eq, left, term())
    else if accept(">") then Expr.Compare(Op.Gt, left, term())
    else left

  def term(): Expr =
    spaces()
    if pos >= source.length then fail("expected a value")
    source(pos) match
      case '.'                      => path()
      case '"'                      => string()
      case c if c.isDigit || c == '-' => number()
      case _ if acceptWord("select") =>
        expect("(")
        val cond = pipe()
        expect(")")
        Expr.Select(cond)
      case c => fail(s"unexpected '$c'")

  /** `.`, then names separated by dots. */
  private def path(): Expr =
    pos += 1 // the first '.'
    if !nameAt(pos) then Expr.Identity
    else
      var e: Expr = Expr.Field(Expr.Identity, name())
      while pos < source.length && source(pos) == '.' && nameAt(pos + 1) do
        pos += 1
        e = Expr.Field(e, name())
      e

  private def nameAt(i: Int): Boolean = i < source.length && (source(i).isLetter || source(i) == '_')

  private def name(): String =
    val start = pos
    while pos < source.length && (source(pos).isLetterOrDigit || source(pos) == '_') do pos += 1
    source.substring(start, pos)

  private def string(): Expr =
    val end = source.indexOf('"', pos + 1)
    if end < 0 then fail("unterminated string")
    val s = source.substring(pos + 1, end)
    pos = end + 1
    Expr.Literal(Value.Str(s))

  private def number(): Expr =
    val start = pos
    pos += 1
    while pos < source.length && source(pos).isDigit do pos += 1
    source.substring(start, pos).toLongOption match
      case Some(n) => Expr.Literal(Value.Num(n))
      case None    => fail("invalid number")

  private def spaces(): Unit = while pos < source.length && source(pos) == ' ' do pos += 1

  private def accept(token: String): Boolean =
    spaces()
    if source.startsWith(token, pos) then { pos += token.length; true } else false

  /** A keyword: not the start of a longer name (`selection` is not `select`). */
  private def acceptWord(word: String): Boolean =
    spaces()
    val after = pos + word.length
    if source.startsWith(word, pos) && !(after < source.length && source(after).isLetterOrDigit) then
      pos = after
      true
    else false

  private def expect(token: String): Unit = if !accept(token) then fail(s"expected '$token'")

  def end(): Unit =
    spaces()
    if pos < source.length then fail(s"unexpected '${source(pos)}'")

  private def fail(message: String): Nothing = throw ParseError(s"$message at $pos in: $source")
