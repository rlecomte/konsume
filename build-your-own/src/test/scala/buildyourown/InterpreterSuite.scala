package buildyourown

import Examples.*

class InterpreterSuite extends munit.FunSuite:

  test("mini-Avro: the bytes of a record, and back") {
    val bytes = Binary.encode(Value.Obj(List("name" -> Value.Str("jo"), "user" -> Value.Obj(List("age" -> Value.Num(30))))),
      Schema.SRecord(List("name" -> Schema.SString, "user" -> Schema.SRecord(List("age" -> Schema.SInt)))))
    assertEquals(bytes.map(b => f"${b & 0xff}%02X").mkString(" "), "02 6A 6F 00 00 00 1E")
    assertEquals(Binary.encode(john, event).length, 40) // as chapter 2 says
    for r <- List(john, jane, record("", -5, "x")) do assertEquals(Binary.decode(Binary.encode(r, event), event), r)
  }

  private def run(program: String, record: Value) = Reference.run(Parser.parse(program), event, Binary.encode(record, event))

  test("filters emit the record itself, projections a value as JSON") {
    assertEquals(run("select(.user.age > 30)", john), List(Output.Original))
    assertEquals(run("select(.user.age > 30)", jane), Nil)
    assertEquals(run(".user", jane), List(Output.Text("""{"age":17,"country":"US"}""")))
    assertEquals(run("""select(.user.country == "FR") | .name""", john), List(Output.Text("\"john\"")))
    assertEquals(run(".user | select(.age > 18) | .country", jane), Nil)
    assertEquals(run(".", jane), List(Output.Original))
  }

  test("comparisons, missing fields") {
    assertEquals(run(".user.age > 18", john), List(Output.Text("true")))
    assertEquals(run(".name > .user.age", john), List(Output.Text("false")))   // a string and a number
    assertEquals(run(".missing", john), List(Output.Text("null")))
    assertEquals(run(".name.x == .missing", john), List(Output.Text("true"))) // null == null
  }
