package konsume.planned

import io.circe.Json
import konsume.json.JsonCursor

import java.nio.charset.StandardCharsets.UTF_8

/** Writes JSON text into a buffer reused from record to record: the very bytes circe's
  * `noSpaces` writes for the same values, without building a `Json`. One per frame.
  *
  * What circe writes, and so what this writes:
  *   - numbers: `Long.toString`, `Float.toString`, `Double.toString`; NaN and infinities are
  *     `null` (`Json.fromDoubleOrNull`);
  *   - strings: `"` and `\` escaped, control characters as `\b \t \n \f \r` or `\u00XX` (lower
  *     case), and so are U+007F to U+009F; everything else as is (UTF-8).
  */
final class JsonOut:
  import JsonOut.*

  private var buf    = new Array[Byte](InitialSize)
  private var n      = 0
  private val digits = new java.lang.StringBuilder(32) // `append(double)` writes no String

  /** The number of bytes written: a mark to go back to with [[reset]]. */
  def size: Int = n

  def reset(mark: Int): Unit = n = mark

  /** The bytes written, in a new array; the buffer is emptied for the next value. */
  def result(): Array[Byte] =
    val out = java.util.Arrays.copyOf(buf, n)
    n = 0
    if buf.length > MaxKept then buf = new Array[Byte](InitialSize) // one huge value is not kept
    out

  private def room(k: Int): Unit =
    if n + k > buf.length then buf = java.util.Arrays.copyOf(buf, math.max(buf.length * 2, n + k))

  def byte(b: Int): Unit =
    room(1)
    buf(n) = b.toByte
    n += 1

  def bytes(bs: Array[Byte]): Unit =
    room(bs.length)
    System.arraycopy(bs, 0, buf, n, bs.length)
    n += bs.length

  def long(x: Long): Unit =
    if x == Long.MinValue then bytes(LongMin)
    else
      var v      = math.abs(x)
      var digits = 1
      var t      = v
      while t >= 10 do
        t /= 10
        digits += 1
      val k = if x < 0 then digits + 1 else digits
      room(k)
      var i = n + k - 1
      while i >= n + k - digits do
        buf(i) = ('0' + (v % 10)).toByte
        v /= 10
        i -= 1
      if x < 0 then buf(n) = '-'
      n += k

  def double(x: Double): Unit =
    if !java.lang.Double.isFinite(x) then bytes(Null)
    else
      digits.setLength(0)
      digits.append(x)
      ascii(digits)

  def float(x: Float): Unit =
    if !java.lang.Float.isFinite(x) then bytes(Null)
    else
      digits.setLength(0)
      digits.append(x)
      ascii(digits)

  private def ascii(cs: java.lang.StringBuilder): Unit =
    room(cs.length)
    var i = 0
    while i < cs.length do
      buf(n + i) = cs.charAt(i).toByte
      i += 1
    n += cs.length

  /** The string whose UTF-8 bytes are `src[off, off + len)`, quoted and escaped. False, with
    * nothing written, if they are not well-formed UTF-8: the reference decodes them with U+FFFD
    * replacements, which the caller must then do.
    */
  def utf8String(src: Array[Byte], off: Int, len: Int): Boolean =
    val mark = n
    val end  = off + len
    room(len + 2)
    buf(n) = '"'
    n += 1
    var i = off
    while i < end do
      val b = src(i) & 0xff
      if b >= 0x20 && b < 0x7f && b != '"' && b != '\\' then // most of them
        if n == buf.length then room(1)
        buf(n) = b.toByte
        n += 1
        i += 1
      else if b < 0x80 then
        escape(b)
        i += 1
      else
        val size = Utf8.length(src, i, end)
        if size < 0 then
          n = mark
          return false
        val b1 = src(i + 1) & 0xff
        if size == 2 && b == 0xc2 && b1 <= 0x9f then escape(b1) // U+0080 to U+009F
        else
          room(size)
          System.arraycopy(src, i, buf, n, size)
          n += size
        i += size
    byte('"')
    true

  /** A JSON value of the payload, already validated (`src[off, off + len)`), written without its
    * spaces: what circe writes for it (numbers keep their text, strings are escaped again), but
    * for an object with a key seen twice, written as it comes (circe keeps one, with the last
    * value). False, with nothing written, when a string is not well-formed UTF-8: circe replaces
    * the ill-formed parts, which the caller must then do.
    */
  def compact(src: Array[Byte], off: Int, len: Int): Boolean =
    val mark = n
    val end  = off + len
    var i    = off
    while i < end do
      val b = src(i)
      if b == '"' then
        var j       = i + 1 // the closing quote
        var escaped = false
        while src(j) != '"' do
          if src(j) == '\\' then
            escaped = true
            j += 2
          else j += 1
        val ok =
          if !escaped then utf8String(src, i + 1, j - i - 1)
          else if wellFormed(src, i + 1, j) then { string(JsonCursor.unescape(src, i + 1, j - i - 1)); true }
          else false
        if !ok then
          n = mark
          return false
        i = j + 1
      else
        if b != ' ' && b != '\n' && b != '\r' && b != '\t' then byte(b) // a space between tokens: dropped
        i += 1
    true

  private def wellFormed(src: Array[Byte], from: Int, until: Int): Boolean =
    var i = from
    while i < until do
      val size = Utf8.length(src, i, until)
      if size < 0 then return false
      i += size
    true

  /** A string, quoted and escaped as circe does. */
  def string(s: String): Unit = bytes(Json.fromString(s).noSpaces.getBytes(UTF_8))

  /** Any value, through its JSON view: for the few values written without a fast path. */
  def json(j: Json): Unit = bytes(j.noSpaces.getBytes(UTF_8))

  private def escape(c: Int): Unit =
    room(6)
    buf(n) = '\\'
    c match
      case '"'  => buf(n + 1) = '"'; n += 2
      case '\\' => buf(n + 1) = '\\'; n += 2
      case '\b' => buf(n + 1) = 'b'; n += 2
      case '\t' => buf(n + 1) = 't'; n += 2
      case '\n' => buf(n + 1) = 'n'; n += 2
      case '\f' => buf(n + 1) = 'f'; n += 2
      case '\r' => buf(n + 1) = 'r'; n += 2
      case _ =>
        buf(n + 1) = 'u'
        buf(n + 2) = '0'
        buf(n + 3) = '0'
        buf(n + 4) = Hex(c >> 4)
        buf(n + 5) = Hex(c & 0xf)
        n += 6

object JsonOut:
  private final val InitialSize = 256
  /** A buffer grown beyond this for one value is dropped after it. */
  final val MaxKept = 1 << 20

  private val Hex     = "0123456789abcdef".getBytes(UTF_8)
  private val Null    = "null".getBytes(UTF_8)
  private val LongMin = Long.MinValue.toString.getBytes(UTF_8)
