package buildyourown

class DemandSuite extends munit.FunSuite:

  private def demand(program: String): Set[String] =
    Demand.of(Parser.parse(program)).map(p => p.map("." + _).mkString)

  test("the paths compared, and the path emitted") {
    assertEquals(demand("select(.user.age > 30)"), Set(".user.age"))
    assertEquals(demand("""select(.user.country == "FR") | .name"""), Set(".user.country", ".name"))
    assertEquals(demand(".user"), Set(".user"))
    assertEquals(demand(".name == .user.country"), Set(".name", ".user.country"))
  }

  test("a path gone through is not used, the record emitted is not read") {
    assertEquals(demand(".user.age"), Set(".user.age")) // not .user
    assertEquals(demand("select(.name == \"x\")"), Set(".name")) // not the record
    assertEquals(demand("."), Set.empty[String])
    assertEquals(demand("30"), Set.empty[String])
  }

  test("a pipe changes the input's path, select keeps it") {
    assertEquals(demand(".user | select(.age > 18) | .country"), Set(".user.age", ".user.country"))
    assertEquals(demand("select(.user.age > 1) | select(.name == \"x\") | .comment"), Set(".user.age", ".name", ".comment"))
  }

  test("a value compared whole") {
    assertEquals(demand("select(. == .user)"), Set("", ".user")) // the record itself, compared: read
  }
