package konsume.planned

import java.nio.charset.StandardCharsets.UTF_8
import java.util.regex.Pattern

/** `test(re)` on a string still in the payload (UTF-8 bytes), without allocating: one per thread
  * and `test` of the program, kept in a register of the frame.
  *
  * `java.util.regex` reads a `CharSequence`: this one is a buffer of chars, reused from record
  * to record, into which the bytes are decoded. Ill-formed UTF-8 (rare: Avro and JSON strings are
  * UTF-8 by definition) is decoded by `String` instead, which replaces it by U+FFFD exactly as
  * the reference does.
  */
private[planned] final class RegexOnBytes(pattern: Pattern) extends CharSequence:
  private var buffer  = new Array[Char](64) // not `chars`: a method of CharSequence
  private var n       = 0
  private val matcher = pattern.matcher("")

  def find(bytes: Array[Byte], off: Int, len: Int): Boolean =
    if len > RegexOnBytes.MaxKept then return pattern.matcher(String(bytes, off, len, UTF_8)).find() // not kept
    // one UTF-8 byte gives at most one char (4 bytes: 2 chars)
    if buffer.length < len then buffer = new Array[Char](math.min(math.max(len, buffer.length * 2), RegexOnBytes.MaxKept))
    n = Utf8.decode(bytes, off, len, buffer)
    if n >= 0 then matcher.reset(this).find()
    else
      val found = matcher.reset(String(bytes, off, len, UTF_8)).find()
      matcher.reset("") // do not keep the string
      found

  def length: Int                                = n
  def charAt(i: Int): Char                       = buffer(i)
  def subSequence(a: Int, b: Int): CharSequence = String(buffer, a, b - a) // not used by find()
  override def toString: String                  = String(buffer, 0, n)

private[planned] object RegexOnBytes:
  /** A longer string is decoded for its record only: the buffer must not keep it. */
  final val MaxKept = 65536

/** UTF-8 to UTF-16, for well-formed UTF-8 only (Unicode, table 3-7). */
private[planned] object Utf8:

  /** Decodes `bytes[off, off + len)` into `out` (at least `len` chars): the number of chars, or
    * -1 at the first ill-formed sequence.
    */
  def decode(bytes: Array[Byte], off: Int, len: Int, out: Array[Char]): Int =
    val end = off + len
    var i   = off
    var n   = 0
    while i < end do
      val b0 = bytes(i) & 0xff
      if b0 < 0x80 then // ASCII: one byte, one char
        out(n) = b0.toChar
        n += 1
        i += 1
      else
        // the size of the sequence, and the range of its second byte
        var size = 0
        var lo   = 0x80
        var hi   = 0xbf
        if b0 >= 0xc2 && b0 <= 0xdf then size = 2
        else if b0 == 0xe0 then { size = 3; lo = 0xa0 } // not overlong
        else if b0 == 0xed then { size = 3; hi = 0x9f } // not a surrogate
        else if b0 >= 0xe1 && b0 <= 0xef then size = 3
        else if b0 == 0xf0 then { size = 4; lo = 0x90 } // not overlong
        else if b0 == 0xf4 then { size = 4; hi = 0x8f } // not above U+10FFFF
        else if b0 >= 0xf1 && b0 <= 0xf3 then size = 4
        else return -1                                  // 80-C1, F5-FF: never a first byte
        if i + size > end then return -1                // cut by the end
        val b1 = bytes(i + 1) & 0xff
        if b1 < lo || b1 > hi then return -1
        // the code point: the useful bits of each byte, end to end
        var cp = (b0 & (0xff >> (size + 1))) << 6 | (b1 & 0x3f)
        var k  = 2
        while k < size do
          val b = bytes(i + k) & 0xff
          if b < 0x80 || b > 0xbf then return -1
          cp = cp << 6 | (b & 0x3f)
          k += 1
        if cp >= 0x10000 then // two chars in UTF-16
          out(n) = Character.highSurrogate(cp)
          out(n + 1) = Character.lowSurrogate(cp)
          n += 2
        else
          out(n) = cp.toChar
          n += 1
        i += size
    n
