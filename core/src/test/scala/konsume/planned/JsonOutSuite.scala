package konsume.planned

import io.circe.Json

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Random

/** The writer against circe: the very bytes `noSpaces` writes. */
class JsonOutSuite extends munit.FunSuite:

  private val out = JsonOut()

  private def same(written: => Unit, expected: Json)(using munit.Location): Unit =
    out.byte('[') // written after something: offsets other than 0
    written
    val bytes = out.result()
    assertEquals(String(bytes, 1, bytes.length - 1, UTF_8), expected.noSpaces)

  test("longs") {
    val rnd = Random(1)
    val xs  = List(0L, 1L, -1L, 9L, 10L, -10L, 99L, 100L, Int.MaxValue, Int.MinValue, Long.MaxValue, Long.MinValue, Long.MinValue + 1) ++
      List.fill(2000)(rnd.nextLong() >> rnd.nextInt(64))
    xs.foreach(x => same(out.long(x), Json.fromLong(x)))
  }

  test("doubles and floats, NaN and infinities as null") {
    val rnd = Random(2)
    val ds = List(0.0, -0.0, 1.0, 0.1, 1e7, 1e-3, 1e21, 1e22, 1.5e-7, Double.MaxValue, Double.MinPositiveValue, Double.NaN,
      Double.PositiveInfinity, Double.NegativeInfinity) ++ List.fill(5000)(java.lang.Double.longBitsToDouble(rnd.nextLong()))
    ds.foreach(d => same(out.double(d), Json.fromDoubleOrNull(d)))
    val fs = List(0f, -0f, 1f, 0.1f, 1e10f, 123456.79f, Float.MaxValue, Float.MinPositiveValue, Float.NaN, Float.NegativeInfinity) ++
      List.fill(5000)(java.lang.Float.intBitsToFloat(rnd.nextInt()))
    fs.foreach(f => same(out.float(f), Json.fromFloatOrNull(f)))
  }

  private def string(s: String)(using munit.Location): Unit =
    val bytes = s.getBytes(UTF_8)
    same(assert(out.utf8String(Array[Byte](1, 2) ++ bytes ++ Array[Byte](3), 2, bytes.length)), Json.fromString(s))

  test("strings: every character to escape, and the others") {
    (0 to 0x2ff).foreach(c => string(s"a${c.toChar}b"))
    List("", "\"", "\\", "/", "€", "😀", "  ", "﻿", "\u0080\u009f ", "ab\u0000\u001f\u007fcd", "x" * 1000).foreach(string)
  }

  test("strings: random text") {
    val rnd = Random(3)
    for _ <- 1 to 3000 do
      val cps = Array.fill(rnd.nextInt(30)) {
        rnd.nextInt(5) match
          case 0 => rnd.nextInt(0x100)
          case 1 => rnd.nextInt(0x800)
          case 2 => rnd.nextInt(0xd800)
          case 3 => 0xe000 + rnd.nextInt(0x2000)
          case _ => 0x10000 + rnd.nextInt(0x100000)
      }
      string(String(cps, 0, cps.length))
  }

  test("ill-formed UTF-8: false, nothing written") {
    val strict = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    def wellFormed(bs: Array[Byte]) =
      try { strict.decode(ByteBuffer.wrap(bs)); true }
      catch case _: CharacterCodingException => false
    val rnd = Random(4)
    for _ <- 1 to 20000 do
      val bs = Array.fill(1 + rnd.nextInt(6))(rnd.nextInt(256).toByte)
      out.byte('[')
      val ok = out.utf8String(bs, 0, bs.length)
      assertEquals(ok, wellFormed(bs), bs.map(b => f"${b & 0xff}%02X").mkString(" "))
      val written = out.result()
      if ok then assertEquals(String(written, 1, written.length - 1, UTF_8), Json.fromString(String(bs, UTF_8)).noSpaces)
      else assertEquals(written.toVector, Vector('['.toByte))
  }

  test("a mark to go back to, a huge value not kept") {
    out.bytes("{\"a\":".getBytes(UTF_8))
    val mark = out.size
    out.long(12345)
    out.reset(mark)
    out.long(7)
    out.byte('}')
    assertEquals(String(out.result(), UTF_8), """{"a":7}""")
    val huge = "é" * (JsonOut.MaxKept / 2 + 1)
    string(huge)
    string("small")
  }
