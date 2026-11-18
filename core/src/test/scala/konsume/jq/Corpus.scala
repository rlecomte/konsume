package konsume.jq

import io.circe.Json
import io.circe.parser.parse

object Corpus:

  val programs: List[String] = List(
    ".",
    ".name",
    ".a.b",
    """.["name"]""",
    ".items[0]",
    ".items[-1]",
    ".items[]",
    ".items[] | select(. > 1)",
    "select(.age > 30)",
    """select(.age >= 30 and .country == "FR")""",
    """select(.age < 18 or .country != "FR")""",
    """select(.name | test("^jo"))""",
    """select(.name | startswith("jo"))""",
    """select(.name | endswith("ne"))""",
    """select(.name | test(.country))""",
    ".name | length",
    ".items | length",
    "length",
    "select((.tags | length) > 1)",
    "select(.age == null)",
    "select(.age != null)",
    "select(.active)",
    "select(.active == false)",
    "select(.active | not)",
    "select(.active == true | not)",
    """.age > "a"""",
    ".age < null",
    ".age >= false",
    "select(.missing.deep == null)",
    ".items[] > 1",
    "true and (.items[] > 1)",
    ".active and (.items[] > 1)",
    ".active or (.items[] > 1)",
    "select(.items[] > 1)",
    "select(.items[] > 1) | .name",
    "1 == 1",
    """"a" < "b"""",
    "null < false",
    "select(false)",
    "select(true and .active)",
    "select(false or .active)",
    ".age == 42.0",
    ".score > 1.5",
    """select(.name > "jo")""",
    ".tags[0] | test(\"a\")",
    "(.age > 18) == .active",
    ".tags == .items",
    "empty",
    "select(.items[] == 5 and .country == \"FR\")",
    "(.items[] > 1) and (.items[] > 2)"
  )

  val inputs: List[Json] = List(
    """{"name":"john","age":42,"country":"FR","active":true,"items":[1,2,3],"tags":["a","b"],"score":2.5}""",
    """{"name":"jane","age":17,"country":"US","active":false,"items":[],"tags":[],"score":1}""",
    """{"name":"joe","age":30,"country":"FR","items":[5],"tags":["x"]}""",
    """{"name":"émile","age":null,"country":null,"active":null}""",
    """{"name":"jo","age":"42","country":"j"}""",
    """{"a":{"b":1},"items":"notarray","name":3}""",
    """{}""",
    "null",
    "42",
    "\"str\"",
    "[1,2]"
  ).map(s => parse(s).fold(throw _, identity))

  /** All outputs, or None when the program fails on this input. */
  def interpret(program: Jq, input: Json): Option[Vector[Json]] =
    try Some(Interpreter.all(program, input))
    catch case _: JqError => None
