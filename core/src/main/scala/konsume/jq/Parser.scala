package konsume.jq

import io.circe.Json

final case class ParseError(message: String, position: Int)
    extends Exception(s"jq parse error at $position: $message")

/** Recursive-descent parser for the supported jq subset.
  *
  * {{{
  * pipe    := or ('|' pipe)?
  * or      := and ('or' and)*
  * and     := cmp ('and' cmp)*
  * cmp     := postfix (('=='|'!='|'<'|'<='|'>'|'>=') postfix)?
  * postfix := primary suffix*
  * suffix  := '.' name | '[' ']' | '[' int ']' | '[' string ']' | '.' '[' ... ']'
  * primary := '.' name? | '.' '[' ... ']' | literal | '(' pipe ')' | builtin
  * }}}
  */
object Parser:

  def parse(input: String): Either[ParseError, Jq] =
    try Right(new Parser(input).program())
    catch case e: ParseError => Left(e)

  private enum Token:
    case Dot, LBracket, RBracket, LParen, RParen, Pipe, Eof
    case Op(op: CmpOp)
    case Ident(name: String)
    case Str(value: String)
    case Num(value: BigDecimal)

  private final class Parser(src: String):
    import Token.*

    private var pos      = 0
    private var tokStart = 0
    private var current  = next()

    def program(): Jq =
      val e = pipe()
      if current != Eof then fail(s"unexpected $current")
      e

    // ---------------------------------------------------------------- grammar

    private def pipe(): Jq =
      val left = or()
      if accept(Pipe) then Jq.Pipe(left, pipe()) else left

    private def or(): Jq =
      var left = and()
      while acceptKeyword("or") do left = Jq.Or(left, and())
      left

    private def and(): Jq =
      var left = cmp()
      while acceptKeyword("and") do left = Jq.And(left, cmp())
      left

    private def cmp(): Jq =
      val left = postfix()
      current match
        case Op(op) =>
          advance()
          Jq.Compare(op, left, postfix())
        case _ => left

    private def postfix(): Jq =
      var e    = primary()
      var loop = true
      while loop do
        current match
          case Dot =>
            advance()
            e = if current == LBracket then { advance(); bracket(e) } else Jq.Field(e, name())
          case LBracket =>
            advance()
            e = bracket(e)
          case _ => loop = false
      e

    /** After '[': `]`, `int]` or `"str"]`. */
    private def bracket(target: Jq): Jq =
      val res = current match
        case RBracket => return { advance(); Jq.Iterate(target) }
        case Num(n) if n.isValidInt => Jq.Index(target, n.toInt)
        case Str(s)                 => Jq.Field(target, s)
        case t                      => fail(s"unsupported index $t")
      advance()
      expect(RBracket)
      res

    private def name(): String = current match
      case Ident(n) => advance(); n
      case Str(s)   => advance(); s
      case t        => fail(s"expected a field name, got $t")

    private def primary(): Jq = current match
      case Dot =>
        advance()
        current match
          case Ident(n) => advance(); Jq.Field(Jq.Identity, n)
          case Str(s)   => advance(); Jq.Field(Jq.Identity, s)
          case LBracket => advance(); bracket(Jq.Identity)
          case _        => Jq.Identity
      case Num(n) =>
        advance()
        Jq.Literal(if n.isValidLong then Json.fromLong(n.toLong) else Json.fromDoubleOrNull(n.toDouble))
      case Str(s) =>
        advance()
        Jq.Literal(Json.fromString(s))
      case LParen =>
        advance()
        val e = pipe()
        expect(RParen)
        e
      case Ident(id) =>
        advance()
        builtin(id)
      case t => fail(s"unexpected $t")

    private def builtin(id: String): Jq = id match
      case "true"       => Jq.Literal(Json.True)
      case "false"      => Jq.Literal(Json.False)
      case "null"       => Jq.Literal(Json.Null)
      case "not"        => Jq.Not
      case "empty"      => Jq.Empty
      case "length"     => Jq.Length
      case "select"     => Jq.Select(argument())
      case "test"       => Jq.Test(argument())
      case "startswith" => Jq.StartsWith(argument())
      case "endswith"   => Jq.EndsWith(argument())
      case other        => fail(s"unknown function '$other'")

    private def argument(): Jq =
      expect(LParen)
      val e = pipe()
      expect(RParen)
      e

    // ------------------------------------------------------------------ utils

    private def accept(t: Token): Boolean =
      if current == t then { advance(); true }
      else false

    private def acceptKeyword(k: String): Boolean = accept(Ident(k))

    private def expect(t: Token): Unit =
      if !accept(t) then fail(s"expected $t, got $current")

    private def fail(msg: String): Nothing = throw ParseError(msg, tokStart)

    private def advance(): Unit = current = next()

    // ------------------------------------------------------------------ lexer

    private def next(): Token =
      while pos < src.length && src.charAt(pos).isWhitespace do pos += 1
      tokStart = pos
      if pos >= src.length then return Eof
      val c = src.charAt(pos)
      pos += 1
      c match
        case '.' => Dot
        case '[' => LBracket
        case ']' => RBracket
        case '(' => LParen
        case ')' => RParen
        case '|' => Pipe
        case '"' => string()
        case '=' if peek('=') => pos += 1; Op(CmpOp.Eq)
        case '!' if peek('=') => pos += 1; Op(CmpOp.Neq)
        case '<'              => if peek('=') then { pos += 1; Op(CmpOp.Lte) } else Op(CmpOp.Lt)
        case '>'              => if peek('=') then { pos += 1; Op(CmpOp.Gte) } else Op(CmpOp.Gt)
        case '-' if pos < src.length && src.charAt(pos).isDigit => number(pos - 1)
        case d if d.isDigit                                      => number(pos - 1)
        case l if l.isLetter || l == '_' =>
          while pos < src.length && (src.charAt(pos).isLetterOrDigit || src.charAt(pos) == '_') do pos += 1
          Ident(src.substring(tokStart, pos))
        case other => fail(s"unexpected character '$other'")

    private def peek(c: Char): Boolean = pos < src.length && src.charAt(pos) == c

    private def number(start: Int): Token =
      pos = start + 1
      while pos < src.length && (src.charAt(pos).isDigit || src.charAt(pos) == '.') do pos += 1
      if pos < src.length && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E') then
        pos += 1
        if pos < src.length && (src.charAt(pos) == '+' || src.charAt(pos) == '-') then pos += 1
        while pos < src.length && src.charAt(pos).isDigit do pos += 1
      try Num(BigDecimal(src.substring(start, pos)))
      catch case _: NumberFormatException => fail(s"invalid number ${src.substring(start, pos)}")

    private def string(): Token =
      val sb = new StringBuilder
      while
        if pos >= src.length then fail("unterminated string")
        val c = src.charAt(pos)
        pos += 1
        c match
          case '"' => false
          case '\\' =>
            if pos >= src.length then fail("unterminated string")
            val e = src.charAt(pos)
            pos += 1
            e match
              case '"' | '\\' | '/' => sb += e
              case 'n'              => sb += '\n'
              case 't'              => sb += '\t'
              case 'r'              => sb += '\r'
              case 'b'              => sb += '\b'
              case 'f'              => sb += '\f'
              case 'u' if pos + 4 <= src.length =>
                sb += Integer.parseInt(src.substring(pos, pos + 4), 16).toChar
                pos += 4
              case '(' => fail("string interpolation is not supported")
              case o   => fail(s"invalid escape \\$o")
            true
          case other =>
            sb += other
            true
      do ()
      Str(sb.result())
