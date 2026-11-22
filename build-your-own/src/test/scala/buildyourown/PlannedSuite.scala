package buildyourown

import Examples.*

class PlannedSuite extends munit.FunSuite:

  private val records =
    List(john, jane, record("bob", 30, "FR"), record("", -1, ""), record("say \"hi\" \\o/", Int.MinValue, "é€"))

  private def same(program: String)(using munit.Location): Unit =
    val expr   = Parser.parse(program)
    val engine = Planned.compile(expr, event).getOrElse(fail(s"not handled: $program"))
    for r <- records do
      val bytes = Binary.encode(r, event)
      assertEquals(engine.run(bytes), Reference.run(expr, event, bytes), s"$program on $r")

  test("filters: the same outputs as the interpreter") {
    List(
      "select(.user.age > 30)", """select(.user.country == "FR") | .name""", """select(.name > "jo")""",
      """select(.user.age == "42")""", """select(.name == 1)""", "select(.missing == .name.x) | .comment",
      "select(30 > .user.age)", "select(.name == .comment)", "select(.user.age > 1) | select(.user.age > 40)",
      "."
    ).foreach(same)
  }

  test("projections: written from the slots, the very text of the interpreter") {
    List(".name", ".user.age", ".user | .country", ".missing", ".name.x", """select(.user.age > 0) | .user.country""").foreach(same)
  }

  test("what the planned engine leaves to the interpreter") {
    for p <- List(".user", "select(.user == .user)", ".user.age > 30", "30", "select(.name)", "select(.user | .age > 30)") do
      assertEquals(Planned.compile(Parser.parse(p), event), None, p)
  }
