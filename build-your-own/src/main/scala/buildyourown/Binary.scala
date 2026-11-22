package buildyourown

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

import Schema.*

/** mini-Avro: values written one after the other, as the schema says, with nothing else (no
  * field names, no types, no separators).
  *
  *   - an int: 4 bytes, big endian;
  *   - a string: its length on 1 byte, then its UTF-8 bytes;
  *   - a record: its fields, in the order of the schema.
  *
  * `{name: "jo", user: {age: 30}}` with the schema `{name: string, user: {age: int}}` is
  * `02 6A 6F 00 00 00 1E`: the length of "jo", "jo", then 30.
  */
object Binary:

  def encode(value: Value, schema: Schema): Array[Byte] =
    val out = ByteArrayOutputStream()
    write(value, schema, out)
    out.toByteArray

  private def write(value: Value, schema: Schema, out: ByteArrayOutputStream): Unit = (value, schema) match
    case (Value.Num(n), SInt) =>
      out.write((n >> 24).toInt)
      out.write((n >> 16).toInt)
      out.write((n >> 8).toInt)
      out.write(n.toInt)
    case (Value.Str(s), SString) =>
      val bytes = s.getBytes(UTF_8)
      require(bytes.length < 256, "a string has at most 255 bytes")
      out.write(bytes.length)
      out.write(bytes)
    case (Value.Obj(values), SRecord(fields)) =>
      for (name, s) <- fields do write(values.collectFirst { case (`name`, v) => v }.get, s, out)
    case _ => throw IllegalArgumentException(s"$value is not a $schema")

  /** The whole record, as a tree of values: what the interpreter works on. */
  def decode(bytes: Array[Byte], schema: Schema): Value = Reader(bytes, 0).value(schema)

  /** Reads values at `pos`, moving it past them. */
  final class Reader(val bytes: Array[Byte], var pos: Int):
    def value(schema: Schema): Value = schema match
      case SInt            => Value.Num(int())
      case SString         => Value.Str(string())
      case SRecord(fields) => Value.Obj(fields.map((name, s) => name -> value(s)))

    def int(): Int =
      val n = (bytes(pos) & 0xff) << 24 | (bytes(pos + 1) & 0xff) << 16 | (bytes(pos + 2) & 0xff) << 8 | (bytes(pos + 3) & 0xff)
      pos += 4
      n

    def string(): String =
      val n = bytes(pos) & 0xff
      val s = String(bytes, pos + 1, n, UTF_8)
      pos += 1 + n
      s

    /** Moves past a value without decoding it: an int is 4 bytes, a string its length + 1. */
    def skip(schema: Schema): Unit = schema match
      case SInt            => pos += 4
      case SString         => pos += 1 + (bytes(pos) & 0xff)
      case SRecord(fields) => fields.foreach((_, s) => skip(s))
