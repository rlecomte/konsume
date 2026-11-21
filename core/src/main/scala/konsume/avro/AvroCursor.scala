package konsume.avro

import konsume.DecodeError

/** Reads Avro binary encoding in place. Truncated input throws an IndexOutOfBoundsException
  * or a DecodeError: callers treat both as an undecodable record.
  */
final class AvroCursor(var bytes: Array[Byte], var pos: Int):

  /** zigzag varint, for both `int` and `long` */
  def readLong(): Long =
    var b     = bytes(pos) & 0xff
    pos += 1
    var n     = (b & 0x7f).toLong
    var shift = 7
    while (b & 0x80) != 0 do
      if shift > 63 then throw DecodeError("invalid varint")
      b = bytes(pos) & 0xff
      pos += 1
      n |= (b & 0x7fL) << shift
      shift += 7
    (n >>> 1) ^ -(n & 1)

  def readInt(): Int = readLong().toInt

  def readBoolean(): Boolean =
    val b = bytes(pos)
    pos += 1
    b != 0

  def readFloat(): Float =
    val i = (bytes(pos) & 0xff) | (bytes(pos + 1) & 0xff) << 8 | (bytes(pos + 2) & 0xff) << 16 | (bytes(pos + 3) & 0xff) << 24
    pos += 4
    java.lang.Float.intBitsToFloat(i)

  def readDouble(): Double =
    var l = 0L
    var i = 7
    while i >= 0 do
      l = (l << 8) | (bytes(pos + i) & 0xffL)
      i -= 1
    pos += 8
    java.lang.Double.longBitsToDouble(l)

  /** Length of a string or bytes, checked against what is left. */
  def readLength(): Int =
    val n = readLong()
    if n < 0 || n > bytes.length - pos then throw DecodeError(s"invalid length $n")
    n.toInt

  def skip(n: Int): Unit =
    if n < 0 || n > bytes.length - pos then throw DecodeError(s"cannot skip $n bytes")
    pos += n

  def skipVarint(): Unit =
    while (bytes(pos) & 0x80) != 0 do pos += 1
    pos += 1
