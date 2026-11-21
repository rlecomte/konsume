package konsume.json

import io.circe.Json
import io.circe.parser.parse
import konsume.jq.{Corpus, Runtime}
import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Random

/** The planned JSON engine against the reference (circe => interpreter). */
class JsonPlannedSuite extends munit.FunSuite:

  override def munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def records(docs: Seq[String]): Vector[Record[String, Array[Byte]]] =
    docs.zipWithIndex.map((d, i) => Record("t", 0, i.toLong, 0L, s"k$i", d.getBytes(UTF_8))).toVector

  private def check(program: String, batch: Vector[Record[String, Array[Byte]]])(using munit.Location): Unit =
    def run(engine: Engine) = Pipeline
      .forBytes[String](ConsumeConfig(Seq("t"), PayloadFormat.Json, Some(program), engine))
      .fold(e => fail(s"$program: $e"), identity)(batch)
      .toVector
    val expected = run(Engine.Interpreted)
    val actual   = run(Engine.Planned)
    def describe(rs: Vector[Record[String, Array[Byte]]]) = rs.map { r =>
      val v = String(r.value, UTF_8)
      if batch.exists(_.value eq r.value) then s"#${r.offset} (original $v)" else s"#${r.offset} $v"
    }
    // Original bytes or re-encoded JSON: both are JSON here, compare what they mean. (Which one
    // is emitted may differ: the reference emits the original when the output is the very same
    // object as the input, which also happens by chance, `null` being a singleton.)
    def meaning(rs: Vector[Record[String, Array[Byte]]]) =
      rs.map(r => (r.offset, parse(String(r.value, UTF_8)).fold(e => fail(s"invalid output: $e"), identity)))
    // when both re-encode the output, the very same bytes
    // (an object with a key seen twice is copied as it comes, circe keeps one: same meaning)
    for (x, y) <- actual.zip(expected) if !batch.exists(_.value eq x.value) && !batch.exists(_.value eq y.value) && !duplicateKeys(x.value) do
      assertEquals(String(x.value, UTF_8), String(y.value, UTF_8), s"program: $program, record #${x.offset}")
    val (a, e) = (meaning(actual), meaning(expected))
    val same = a.size == e.size && a.zip(e).forall { case ((ao, aj), (eo, ej)) => ao == eo && Runtime.compare(aj, ej) == 0 }
    if !same then
      val diff = (a.map(_._1) ++ e.map(_._1)).distinct.sorted.flatMap { o =>
        val (x, y) = (a.filter(_._1 == o).map(_._2.noSpaces), e.filter(_._1 == o).map(_._2.noSpaces))
        if x == y then None else Some(s"#$o ${String(batch(o.toInt).value, UTF_8).take(80)}\n    actual: $x\n  expected: $y")
      }
      fail(s"program: $program\n${diff.take(5).mkString("\n")}")

  /** Has an object of this JSON a key seen twice? */
  private def duplicateKeys(bytes: Array[Byte]): Boolean =
    val cur = JsonCursor(bytes, 0)
    def value(): Boolean = cur.kind() match
      case JsonCursor.Object =>
        var dup  = false
        val seen = collection.mutable.Set.empty[String]
        if cur.beginObject() then
          var more = true
          while more do
            cur.readKey()
            if !seen.add(JsonCursor.unescape(bytes, cur.keyStart, cur.keyLen)) then dup = true
            dup = value() || dup
            more = cur.nextMember()
        dup
      case JsonCursor.Array =>
        var dup = false
        if cur.beginArray() then
          var more = true
          while more do
            dup = value() || dup
            more = cur.nextElement()
        dup
      case _ =>
        cur.skipValue()
        false
    value()

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

  test("outputs written from the payload are byte for byte those of the reference") {
    val values = List(
      "0", "-0", "7", "-7", "1.0", "-0.0", "1e2", "1E+2", "-1.5e-3", "123456789012345678", "9223372036854775807",
      "-9223372036854775808", "12345678901234567890", "0.1000", "\"plain\"", "\"\"", "\"é\"", "\"😀\"",
      "\"a\\\"b\"", "\"tab\\t\"", "\"\\u0041\"", "\"~\"", "\"\u007f\"", "\"a/b\"", "\"a\\/b\"", "true", "false",
      "null", "[1, 2]", "{\"x\": 1}"
    )
    val batch = records(values.map(v => s"""{"a": $v, "b": {"c": $v}, "n": $v}"""))
    List(".a", ".b.c", "select(.n != 1) | .a", ".a | length").foreach(check(_, batch))
  }

  test("array elements: short arrays, duplicate keys, nested arrays, not an array") {
    val docs = List(
      """{"xs":[1,2,3]}""", """{"xs":[ 1 , [2, 3] , {"a":4} ]}""", """{"xs":[]}""", """{"xs":[7]}""",
      """{"xs":[1,2,3],"xs":[9]}""", """{"xs":[9],"xs":[1,2,3]}""", """{"xs":[1,2],"xs":5}""", """{"xs":{"0":1}}""",
      """{"xs":null}""", """{}""", """{"xs":"abc"}""", """{"xs":[[1,[2,3]],[4]]}""", """{"xs":[{"a":1,"a":2},{"b":[5,6]}]}"""
    )
    val programs = List(
      ".xs[0]", ".xs[1]", ".xs[2]", ".xs[0][1]", ".xs[0][1][0]", ".xs[1].b[1]", ".xs[0].a", "select(.xs[1] > 1)",
      ".xs[0] == .xs[2]", ".xs[1] | length", "select(.xs[0] != null) | .xs[2]", ".xs[2].a", ".xs[1][0]"
    )
    programs.foreach(check(_, records(docs)))
  }

  test("arrays walked: not an array, nested walks, what is left from the previous element") {
    val docs = List(
      """{"xs":[1,2,3],"s":"a"}""", """{"xs":[]}""", """{"xs":null}""", """{}""", """{"xs":5}""", """{"xs":"abc"}""",
      """{"xs":[ 1 , "a\u0041" , true , null , -0.50 ]}""", """[1,[2],{"a":3}]""",
      // an object is iterated on its values: on its JSON view, duplicate keys as circe keeps them
      """{"xs":{"a":1,"b":[2,3]}}""", """{"xs":{"a":1,"a":2,"b":0}}""", """{"xs":{"k":{"ys":[4,5]},"j":{"ys":7}}}""",
      // the last key wins: an array then an object, an object then an array
      """{"xs":[1],"xs":{"a":{"ys":[8]}}}""", """{"xs":{"a":1},"xs":[{"ys":[9]}]}""",
      // nested walks; an element without the array the previous one had
      """{"xs":[{"ys":[1]},5,{"ys":[2,3]}]}""", """{"xs":[{"ys":[1]},{"zs":0},{"ys":[]}]}""",
      """{"xs":[{"ys":[1,2]},{"ys":{"a":6}},{"ys":null}]}""", """{"xs":[[1,2],[],[3,[4]]]}""",
      """{"xs":[{"ys":[{"zs":[1]}]},{"ys":{"k":{"zs":[2]}}}]}""", """{"xs":[{"ys":[1]}],"ys":[0]}"""
    )
    val programs = List(
      ".xs[]", ".xs[] | select(. > 1)", ".xs[] | select(. == true)", ".xs[] | .ys[]", ".xs[] | .ys[] | select(. >= 2)",
      ".xs[][]", ".xs[] | .ys", ".xs[] | .ys[0]", ".xs[] | .ys | length", ".xs[] | length", ".xs[] | .ys[] | .zs[]",
      ".xs[] | select(.ys != null) | .ys[]", """.xs[] | test("A")""", ".xs[] > .xs[]", "select(.xs[] > 1) | .xs[]",
      ".[]", ".[] | select(. != 1)", ".xs[] | .ys[] | 1", ".xs[] | .ys[] | .zs[] | true", ".xs[] | .a", "select(.s == \"a\") | .xs[]"
    )
    programs.foreach(check(_, records(docs)))
  }

  test("objects and arrays emitted whole: copied without spaces, strings escaped as circe does") {
    val docs = List(
      "{\"v\":{ \"a\" : [ 1 , 2 ] , \"s\" : \"a  b , c : d\" }}",          // spaces between tokens, and in a string
      "{\"v\" :\n {\r\n \"a\"\t: 1 ,\"b\":[ ]}\n}",
      "{\"v\":{\"s\":\"\\/\\u0041\\u001F\\n\\ud83d\\ude00\\u2028\\\"\\\\\"}}", // escapes, rewritten by circe
      "{\"v\":[\"\\ud800 x\",\"\\u00e9\",\"\\b\\f\\r\\t\"]}",                // a lone surrogate, escapes of circe's own
      "{\"v\":[\"del \u007f c1 \u0085 \u009f nbsp \u00a0 é 😀\"]}",       // raw U+007F to U+009F: escaped by circe
      "{\"v\":[1E2,1e+2,-0,-0.0,0.10,1.5e-07,12345678901234567890123,1E400,0,true,false,null]}",
      "{\"v\":{}}", "{\"v\":[]}", "{\"v\":{\"a\":{\"b\":[{},[],[[]],{\"c\":[{}]}]}}}",
      "{\"v\":{\"a\":1,\"a\":{\"b\":2,\"b\":3}}}",                            // keys seen twice: copied as they come
      "{\"v\":{\"k\\u0065y\":1,\"key\":2}}"                                   // the same key, once escaped
    )
    val invalid = "{\"v\":{\"s\":\"a".getBytes(UTF_8) ++ Array(0xe9.toByte) ++ "b\",\"t\":[1, 2]}}".getBytes(UTF_8)
    val batch   = records(docs) :+ Record("t", 0, docs.size.toLong, 0L, "k", invalid)
    List(".v", "select(.v != null) | .v", ".v.a", ".v[]", ".v.a.b", ".v | .a").foreach(check(_, batch))
  }

  private val programs = Corpus.programs ++ List(
    "select(.user.age > 30)", """select(.user.country == "FR")""", ".user.age", ".user", """select(.country == "FR")""",
    ".score", "select(.score < 1)", ".items[]", ".tags[]", "select(.age == 100)", "select(.age < 31)", ".age == 40",
    "select(.skip)", ".user.age.x", """select(.name | test("^j.n?e?$"))""", """select(.name | test("^.{1}$"))""",
    """select(.name | test("é|😀"))""", """select(.user.country | test("R$"))"""
  )

  test("corpus documents, every corpus program") {
    val batch = records(Corpus.inputs.map(_.noSpaces))
    programs.foreach(check(_, batch))
  }

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
