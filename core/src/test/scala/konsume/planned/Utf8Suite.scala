package konsume.planned

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Random

/** The decoder against the JDK's: same chars for well-formed UTF-8, -1 exactly where the JDK
  * finds the input malformed (and would put U+FFFD).
  */
class Utf8Suite extends munit.FunSuite:

  private def jdk(bytes: Array[Byte]): Option[String] =
    val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    try Some(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch case _: CharacterCodingException => None

  private def check(bytes: Array[Byte]): Unit =
    val padded = Array[Byte](7, 7) ++ bytes ++ Array[Byte](7) // decoded in the middle of an array
    val out    = new Array[Char](bytes.length)
    val n      = Utf8.decode(padded, 2, bytes.length, out)
    val actual = if n < 0 then None else Some(String(out, 0, n))
    assertEquals(actual, jdk(bytes), bytes.map(b => f"${b & 0xff}%02X").mkString(" "))

  test("every sequence of 1 and 2 bytes") {
    for a <- 0 to 255 do
      check(Array(a.toByte))
      for b <- 0 to 255 do check(Array(a.toByte, b.toByte))
  }

  test("the limits of table 3-7, cut sequences") {
    val cases = List(
      "E0 A0 80", "E0 9F BF", "ED 9F BF", "ED A0 80", "EF BF BF", "F0 90 80 80", "F0 8F BF BF", "F4 8F BF BF",
      "F4 90 80 80", "F5 80 80 80", "C0 80", "C1 BF", "C2 80", "E2 82", "F0 9F 98", "E2 82 AC 41", "41 E2 41"
    )
    cases.foreach(c => check(c.split(' ').map(Integer.parseInt(_, 16).toByte)))
  }

  test("random sequences of 3 and 4 bytes, and random text") {
    val rnd        = Random(3)
    val interesting = Array(0x00, 0x41, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xc2, 0xdf, 0xe0, 0xe1, 0xed, 0xef, 0xf0, 0xf4, 0xf5, 0xff)
    for _ <- 1 to 200000 do
      val n = 3 + rnd.nextInt(2)
      check(Array.fill(n)((if rnd.nextBoolean() then interesting(rnd.nextInt(interesting.length)) else rnd.nextInt(256)).toByte))
    for _ <- 1 to 2000 do
      val text = Iterator.fill(rnd.nextInt(20))(Character.toChars(Iterator.continually(rnd.nextInt(0x10ffff)).find(cp => cp < 0xd800 || cp > 0xdfff).get).mkString).mkString
      check(text.getBytes(UTF_8))
  }
