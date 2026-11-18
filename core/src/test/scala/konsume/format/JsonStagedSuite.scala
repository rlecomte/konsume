package konsume.format

import io.circe.Json
import io.circe.parser.parse
import konsume.jq.{Corpus, Runtime}
import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Random

/** The staged JSON decoder against the reference (circe => interpreter). */
class JsonStagedSuite extends munit.FunSuite:

  override def munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def records(docs: Seq[String]): Vector[Record[String, Array[Byte]]] =
    docs.zipWithIndex.map((d, i) => Record("t", 0, i.toLong, 0L, s"k$i", d.getBytes(UTF_8))).toVector

  private def check(program: String, batch: Vector[Record[String, Array[Byte]]])(using munit.Location): Unit =
    def run(engine: Engine) = Pipeline
      .forBytes[String](ConsumeConfig(Seq("t"), PayloadFormat.Json, Some(program), engine))
      .fold(e => fail(s"$program: $e"), identity)(batch)
      .toVector
    val expected = run(Engine.Interpreted)
    val actual   = run(Engine.Staged)
    def describe(rs: Vector[Record[String, Array[Byte]]]) = rs.map { r =>
      val v = String(r.value, UTF_8)
      if batch.exists(_.value eq r.value) then s"#${r.offset} (original $v)" else s"#${r.offset} $v"
    }
    // Original bytes or re-encoded JSON: both are JSON here, compare what they mean. (Which one
    // is emitted may differ: the reference emits the original when the output is the very same
    // object as the input, which also happens by chance, `null` being a singleton.)
    def meaning(rs: Vector[Record[String, Array[Byte]]]) =
      rs.map(r => (r.offset, parse(String(r.value, UTF_8)).fold(e => fail(s"invalid output: $e"), identity)))
    val (a, e) = (meaning(actual), meaning(expected))
    val same = a.size == e.size && a.zip(e).forall { case ((ao, aj), (eo, ej)) => ao == eo && Runtime.compare(aj, ej) == 0 }
    if !same then
      val diff = (a.map(_._1) ++ e.map(_._1)).distinct.sorted.flatMap { o =>
        val (x, y) = (a.filter(_._1 == o).map(_._2.noSpaces), e.filter(_._1 == o).map(_._2.noSpaces))
        if x == y then None else Some(s"#$o ${String(batch(o.toInt).value, UTF_8).take(80)}\n    actual: $x\n  expected: $y")
      }
      fail(s"program: $program\n${diff.take(5).mkString("\n")}")

  private val tricky = List(
    // duplicate keys: the last one wins, including for nested objects
    """{"user":{"age":40},"user":{"country":"FR"}}""",
    """{"user":{"age":40},"user":5}""",
    """{"user":5,"user":{"age":40,"country":"FR"}}""",
    """{"age":1,"age":"40","age":40}""",
    // escapes, in keys and values
    """{"co\u0075ntry":"FR","name":"a\"b","user":{"c\u006funtry":"F\u0052"}}""",
    """{"name":"jo\u00e9","country":"\u0046R"}""",
    """{"name":"\ud83d\ude00","tags":["\\","\/","\b\f\n\r\t"]}""",
    """{"name":"émile 😀","country":"FR"}""",
    // numbers
    """{"age":1e2,"score":30.0,"items":[-0,0.1,1.5E-3,12345678901234567890]}""",
    """{"age":30.000000000000001,"score":-0.0}""",
    """{"age":1e400,"score":-1e-400}""",
    """{"age":31,"score":1.25e+1,"items":[123456789012345,1234567890123456,0.30000000000000004]}""",
    // whitespace everywhere
    " \n{ \"age\" : 40 ,\r\n\t\"name\" :\"x\" , \"items\" : [ 1 , 2 ] } \t",
    // what the program does not look at is skipped, but validated
    """{"skip":[[[[[[{"a":[1,2,{"b":null}]}]]]]]],"age":40,"name":"joe"}""",
    "{\"skip\":" + "[" * 200 + "]" * 200 + ",\"age\":40}",
    "{\"skip\":" + "[{\"a\":" * 100 + "1" + "}]" * 100 + ",\"age\":40}",
    // not objects at the root, empty containers
    "42", "\"x\"", """[1,{"a":1}]""", "null", "true", "{}", """{"user":{},"items":[]}""",
    """{"user":null,"items":null}""", """{"user":"str","items":{"a":1}}"""
  )

  private val invalid = List(
    "", "   ", """{"age":40,}""", """{"age":}""", """{"age" 40}""", """{age:40}""", """{"age":01}""",
    """{"age":1.}""", """{"age":.5}""", """{"age":-}""", """{"age":1e}""", """{"age":+1}""", """{"a":"\x"}""",
    """{"a":"\u12"}""", """{"a":tru}""", """{"a":nul}""", """{"a":1} x""", """{"a":[1,2}""", """{"a":{"b":1]}""",
    """["a" "b"]""", "{\"a\":\"line\nbreak\"}", """{"a":1}{"b":2}""", "[1,]", "{,}", """{"a":1,,"b":2}""",
    "\"unterminated", """{"a":"\"}""", """{"a":1""", """{"a" """, "[", "]", "{\"age\":40}\u0000",
    """{"age":NaN}""", """{"age":Infinity}""", """{"a":'x'}""", """{"a":TRUE}"""
  )

  private val programs = Corpus.programs ++ List(
    "select(.user.age > 30)", """select(.user.country == "FR")""", ".user.age", ".user", """select(.country == "FR")""",
    ".score", "select(.score < 1)", ".items[]", ".tags[]", "select(.age == 100)", "select(.age < 31)", ".age == 40",
    "select(.skip)", ".user.age.x"
  )

  test("tricky documents, every corpus program") {
    val batch = records(tricky)
    programs.foreach(check(_, batch))
  }

  test("invalid documents are rejected exactly like the reference decoder does") {
    val batch = records(invalid ++ tricky)
    programs.foreach(check(_, batch))
  }

  test("random mutations of valid documents: same acceptance, same outputs") {
    val rnd   = Random(7)
    val chars = """{}[]",:0123456789-.eE+ tfnul\ab/""".toVector
    val docs = Vector.fill(3000) {
      val doc = tricky(rnd.nextInt(tricky.size))
      if doc.isEmpty then doc
      else
        val i = rnd.nextInt(doc.length)
        rnd.nextInt(3) match
          case 0 => doc.patch(i, Seq(chars(rnd.nextInt(chars.size))), 0) // insert
          case 1 => doc.patch(i, Nil, 1)                                  // delete
          case _ => doc.patch(i, Seq(chars(rnd.nextInt(chars.size))), 1) // replace
    }
    val batch = records(docs)
    // the mutations must produce both valid and invalid documents
    val valid = docs.count(d => parse(d).isRight)
    assert(valid > 300 && valid < 2700, s"$valid valid documents out of ${docs.size}")
    List(".", ".age", "select(.user.age > 30)", """select(.name == "joe")""", ".items[]", ".user", "length").foreach(
      check(_, batch)
    )
  }

  test("random documents and programs") {
    val rnd  = Random(42)
    val keys = Vector("a", "b", "c", "user", "age")
    def value(depth: Int): Json = rnd.nextInt(if depth > 3 then 6 else 9) match
      case 0 => Json.Null
      case 1 => Json.fromBoolean(rnd.nextBoolean())
      case 2 => Json.fromInt(rnd.nextInt(100) - 20)
      case 3 => Json.fromDoubleOrNull(rnd.nextDouble() * 100)
      case 4 => Json.fromString(Vector("FR", "jo", "joe", "", "é", "😀", "a\"b", "m")(rnd.nextInt(8)))
      case 5 => Json.fromLong(rnd.nextLong())
      case 6 => Json.fromValues(Vector.fill(rnd.nextInt(4))(value(depth + 1)))
      case _ => Json.fromFields(Vector.fill(rnd.nextInt(4))(keys(rnd.nextInt(keys.size)) -> value(depth + 1)))
    val docs = Vector.fill(300) {
      val j = Json.fromFields(Vector.fill(1 + rnd.nextInt(5))(keys(rnd.nextInt(keys.size)) -> value(0)))
      if rnd.nextBoolean() then j.noSpaces else j.spaces2
    }
    val batch = records(docs)
    def path = Vector.fill(1 + rnd.nextInt(3))("." + keys(rnd.nextInt(keys.size))).mkString
    def literal = Vector("30", "0", "-1", "1.5", "\"FR\"", "\"jo\"", "\"m\"", "null", "true", "false")(rnd.nextInt(10))
    def op      = Vector("==", "!=", "<", "<=", ">", ">=")(rnd.nextInt(6))
    for _ <- 1 to 150 do
      val program = rnd.nextInt(8) match
        case 0 => path
        case 1 => s"select($path $op $literal)"
        case 2 => s"select($path $op $literal and $path $op $literal)"
        case 3 => s"select($literal $op $path) | $path"
        case 4 => s"$path | length"
        case 5 => s"select($path | not)"
        case 6 => s"$path == $path"
        case _ => s"select($path != null) | $path[]"
      check(program, batch)
  }
