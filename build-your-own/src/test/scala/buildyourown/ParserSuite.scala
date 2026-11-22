package buildyourown

import Expr.*

class ParserSuite extends munit.FunSuite:

  private def field(names: String*): Expr = names.foldLeft(Identity: Expr)(Field(_, _))

  test("paths") {
    assertEquals(Parser.parse("."), Identity)
    assertEquals(Parser.parse(".name"), field("name"))
    assertEquals(Parser.parse(".user.age"), field("user", "age"))
  }

  test("literals and comparisons") {
    assertEquals(Parser.parse(".age > 30"), Compare(Op.Gt, field("age"), Literal(Value.Num(30))))
    assertEquals(Parser.parse(""".country == "FR""""), Compare(Op.Eq, field("country"), Literal(Value.Str("FR"))))
    assertEquals(Parser.parse("-5"), Literal(Value.Num(-5)))
  }

  test("a comparison binds tighter than |, | is right-associative") {
    assertEquals(
      Parser.parse("""select(.a > 1) | .c == "x" | .d"""),
      Pipe(Select(Compare(Op.Gt, field("a"), Literal(Value.Num(1)))), Pipe(Compare(Op.Eq, field("c"), Literal(Value.Str("x"))), field("d")))
    )
  }

  test("errors") {
    for bad <- List("", ".a >", "select(.a", ".a ==", "\"open", "select .a", ".a .b", "selection(.a)") do
      intercept[ParseError](Parser.parse(bad))
  }
