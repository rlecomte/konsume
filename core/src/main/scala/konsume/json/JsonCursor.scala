package konsume.json

import konsume.DecodeError
import io.circe.Json

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** Reads and validates JSON (RFC 8259) in place, without allocating: values are located by
  * their offset and length in the payload, numbers are parsed to doubles.
  *
  * Malformed input throws a DecodeError, or an IndexOutOfBoundsException when it ends too early:
  * callers treat both as an undecodable record.
  */
final class JsonCursor(var bytes: Array[Byte], var pos: Int):
  import JsonCursor.*

  /** The last string or number read: where it is, and whether the string has escapes. */
  var valStart: Int       = 0
  var valLen: Int         = 0
  var escaped: Boolean    = false
  /** The last object key read. */
  var keyStart: Int       = 0
  var keyLen: Int         = 0
  var keyEscaped: Boolean = false

  private def fail(msg: String): Nothing = throw DecodeError(s"invalid JSON at $pos: $msg")

  def skipWs(): Unit =
    while pos < bytes.length && { val c = bytes(pos); c == ' ' || c == '\n' || c == '\r' || c == '\t' } do pos += 1

  /** The kind of the value at the cursor (after whitespace); strings are `String`, refined to
    * `EscapedString` by the caller once read.
    */
  def kind(): Int =
    skipWs()
    (bytes(pos): @annotation.switch) match
      case '{'                                                                    => Object
      case '['                                                                    => Array
      case '"'                                                                    => String
      case 't'                                                                    => True
      case 'f'                                                                    => False
      case 'n'                                                                    => Null
      case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => Number
      case c                                                                      => fail(s"unexpected '${c.toChar}'")

  /** Reads a string at the cursor: sets valStart, valLen (the content, without quotes), escaped. */
  def readString(): Unit =
    if bytes(pos) != '"' then fail("expected a string")
    pos += 1
    valStart = pos
    escaped = false
    var c = bytes(pos)
    while c != '"' do
      if c == '\\' then
        escaped = true
        pos += 1
        (bytes(pos): @annotation.switch) match
          case '"' | '\\' | '/' | 'b' | 'f' | 'n' | 'r' | 't' => pos += 1
          case 'u' =>
            var i = 1
            while i <= 4 do
              if hexValue(bytes(pos + i)) < 0 then fail("invalid \\u escape")
              i += 1
            pos += 5
          case _ => fail("invalid escape")
      else if (c & 0xff) < 0x20 then fail("control character in a string")
      else pos += 1
      c = bytes(pos)
    valLen = pos - valStart
    pos += 1

  /** Reads a number at the cursor: sets valStart, valLen, returns its value. */
  def readNumber(): Double =
    val start    = pos
    val negative = bytes(pos) == '-'
    if negative then pos += 1
    var mantissa = 0L
    var digits   = 0 // significant digits in the mantissa, a double is exact up to 15
    var exact    = true
    def digit(): Unit =
      if digits < 15 then
        mantissa = mantissa * 10 + (bytes(pos) - '0')
        if mantissa != 0 then digits += 1
      else exact = false
      pos += 1
    if bytes(pos) == '0' then pos += 1
    else if isDigit(bytes(pos)) then while pos < bytes.length && isDigit(bytes(pos)) do digit()
    else fail("invalid number")
    var fraction = 0
    if pos < bytes.length && bytes(pos) == '.' then
      pos += 1
      if pos >= bytes.length || !isDigit(bytes(pos)) then fail("invalid number")
      while pos < bytes.length && isDigit(bytes(pos)) do
        digit()
        fraction += 1
    if pos < bytes.length && (bytes(pos) == 'e' || bytes(pos) == 'E') then
      exact = false
      pos += 1
      if pos < bytes.length && (bytes(pos) == '+' || bytes(pos) == '-') then pos += 1
      if pos >= bytes.length || !isDigit(bytes(pos)) then fail("invalid number")
      while pos < bytes.length && isDigit(bytes(pos)) do pos += 1
    valStart = start
    valLen = pos - start
    if exact && fraction <= 22 then
      // both exact as doubles: the division is correctly rounded, like parseDouble
      val v = mantissa.toDouble / Pow10(fraction)
      if negative then -v else v
    else java.lang.Double.parseDouble(java.lang.String(bytes, start, valLen, ISO_8859_1))

  private def literal(expected: Array[Byte]): Unit =
    if !java.util.Arrays.equals(bytes, pos, pos + expected.length, expected, 0, expected.length) then
      fail("invalid literal")
    pos += expected.length

  /** At '{': consumes it; false (and consumes the '}') for an empty object. */
  def beginObject(): Boolean =
    pos += 1
    skipWs()
    if bytes(pos) == '}' then
      pos += 1
      false
    else true

  /** Reads `"key" :`, leaves the cursor before the value. */
  def readKey(): Unit =
    skipWs()
    readString()
    keyStart = valStart
    keyLen = valLen
    keyEscaped = escaped
    skipWs()
    if bytes(pos) != ':' then fail("expected ':'")
    pos += 1

  def keyIs(utf8: Array[Byte], name: java.lang.String): Boolean =
    if keyEscaped then unescape(bytes, keyStart, keyLen) == name
    else keyLen == utf8.length && java.util.Arrays.equals(bytes, keyStart, keyStart + keyLen, utf8, 0, keyLen)

  /** After a member: true at ',' (consumed), false at '}' (consumed). */
  def nextMember(): Boolean =
    skipWs()
    bytes(pos) match
      case ',' => pos += 1; true
      case '}' => pos += 1; false
      case _   => fail("expected ',' or '}'")

  /** The end of the payload: only whitespace is left. */
  def end(): Unit =
    skipWs()
    if pos != bytes.length then fail("trailing characters")

  /** Skips (and validates) the value at the cursor. Iterative: no stack overflow on deep input. */
  def skipValue(): Unit =
    var depth = 0
    var inObject = 0L // bit d: is the container at depth d an object (else an array)
    var deep: scala.Array[Boolean] = null // beyond 64 levels
    def setTop(isObject: Boolean): Unit =
      if depth < 64 then inObject = if isObject then inObject | (1L << depth) else inObject & ~(1L << depth)
      else
        if deep == null || deep.length <= depth then deep = java.util.Arrays.copyOf(if deep == null then new scala.Array[Boolean](128) else deep, depth * 2 + 2)
        deep(depth) = isObject
    def topIsObject: Boolean =
      val d = depth - 1
      if d < 64 then (inObject & (1L << d)) != 0 else deep(d)

    var more = true
    while more do
      // a value
      var closed = false
      kind() match
        case Object =>
          if beginObject() then
            setTop(true)
            depth += 1
            readKey()
          else closed = true
        case Array =>
          pos += 1
          skipWs()
          if bytes(pos) == ']' then
            pos += 1
            closed = true
          else
            setTop(false)
            depth += 1
        case String => readString(); closed = true
        case Number => readNumber(); closed = true
        case True   => literal(TrueBytes); closed = true
        case False  => literal(FalseBytes); closed = true
        case _      => literal(NullBytes); closed = true
      // after a complete value: close containers, or move to the next element
      while closed do
        if depth == 0 then
          closed = false
          more = false
        else
          skipWs()
          val c = bytes(pos)
          pos += 1
          if c == ',' then
            if topIsObject then readKey()
            closed = false
          else if c == (if topIsObject then '}' else ']') then depth -= 1
          else fail(s"unexpected '${c.toChar}'")

object JsonCursor:
  // kinds of values: the order is the one of jq's type order (null < false < true < ...)
  final val Null          = 0
  final val False         = 1
  final val True          = 2
  final val Number        = 3
  final val String        = 4
  final val EscapedString = 5
  final val Array         = 6
  final val Object        = 7

  private val TrueBytes  = "true".getBytes(UTF_8)
  private val FalseBytes = "false".getBytes(UTF_8)
  private val NullBytes  = "null".getBytes(UTF_8)
  private val Pow10      = scala.Array.tabulate(23)(i => math.pow(10, i))

  private inline def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'

  private def hexValue(b: Byte): Int =
    if b >= '0' && b <= '9' then b - '0'
    else if b >= 'a' && b <= 'f' then b - 'a' + 10
    else if b >= 'A' && b <= 'F' then b - 'A' + 10
    else -1

  /** The value of a string with escapes, from its content (without quotes). */
  def unescape(bytes: scala.Array[Byte], start: Int, len: Int): java.lang.String =
    val sb  = java.lang.StringBuilder(len)
    val end = start + len
    var run = start // start of the current run without escapes
    var i   = start
    while i < end do
      if bytes(i) == '\\' then
        sb.append(java.lang.String(bytes, run, i - run, UTF_8))
        bytes(i + 1) match
          case 'b' => sb.append('\b'); i += 2
          case 'f' => sb.append('\f'); i += 2
          case 'n' => sb.append('\n'); i += 2
          case 'r' => sb.append('\r'); i += 2
          case 't' => sb.append('\t'); i += 2
          case 'u' =>
            var c = 0
            var k = 2
            while k < 6 do
              c = c * 16 + hexValue(bytes(i + k))
              k += 1
            sb.append(c.toChar)
            i += 6
          case c => sb.append(c.toChar); i += 2 // '"', '\\', '/'
        run = i
      else i += 1
    sb.append(java.lang.String(bytes, run, end - run, UTF_8))
    sb.toString

  /** The JSON view of a value already validated: with circe, as the reference decoder does. */
  def parse(bytes: scala.Array[Byte], start: Int, len: Int): Json =
    io.circe.jawn.parseByteBuffer(ByteBuffer.wrap(bytes, start, len)).fold(e => throw DecodeError(e.message, e), identity)
