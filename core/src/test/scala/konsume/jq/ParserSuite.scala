package konsume.jq

import io.circe.Json
import Jq.*

class ParserSuite extends munit.FunSuite:

  private def ok(src: String, expected: Jq)(using munit.Location): Unit =
    assertEquals(Parser.parse(src), Right(expected))

  test("paths") {
    ok(".", Identity)
    ok(".a", Field(Identity, "a"))
    ok(".a.b", Field(Field(Identity, "a"), "b"))
    ok(""".["a b"]""", Field(Identity, "a b"))
    ok(""".a."b"""", Field(Field(Identity, "a"), "b"))
    ok(".a[0]", Index(Field(Identity, "a"), 0))
    ok(".[-1]", Index(Identity, -1))
    ok(".a[]", Iterate(Field(Identity, "a")))
    ok(".a.[]", Iterate(Field(Identity, "a")))
  }

  test("literals") {
    ok("42", Literal(Json.fromLong(42)))
    ok("-1.5", Literal(Json.fromDoubleOrNull(-1.5)))
    ok(""""a\"é"""", Literal(Json.fromString("a\"é")))
    ok("null", Literal(Json.Null))
  }

  test("precedence: | < or < and < comparison") {
    ok(
      ".a | .b == 1 or .c and not",
      Pipe(
        Field(Identity, "a"),
        Or(Compare(CmpOp.Eq, Field(Identity, "b"), Literal(Json.fromLong(1))), And(Field(Identity, "c"), Not))
      )
    )
  }

  test("builtins") {
    ok("""select(.name | test("^a"))""", Select(Pipe(Field(Identity, "name"), Test(Literal(Json.fromString("^a"))))))
    ok("length", Length)
  }

  test("errors") {
    assert(Parser.parse(".a ==").isLeft)
    assert(Parser.parse("map(.a)").isLeft)
    assert(Parser.parse(""""abc""").isLeft)
    assert(Parser.parse(".a )").isLeft)
  }
