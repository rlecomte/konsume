package buildyourown

import Examples.*
import Instr.*
import PVal.*
import Schema.*

class PlanSuite extends munit.FunSuite:

  private def plan(program: String) = Plan(event, Demand.of(Parser.parse(program)))

  test("select(.user.age > 30): skip the name, read the age, stop") {
    val p = plan("select(.user.age > 30)")
    assertEquals(p.instrs, List(Skip(SString), ReadInt(0)))
    assertEquals(p.values, Map(List("user", "age") -> PInt(0)))
  }

  test("the slots after reading John, with nothing after the age (the bytes cut there)") {
    val p = plan("select(.user.age > 30)")
    val f = Frame(p.slots)
    f.bytes = Binary.encode(john, event).take(1 + 4 + 4) // "john" and 42: the rest is not there
    p.read(f)
    assertEquals(f.nums(0), 42L)
  }

  test("a string stays in the bytes: its offset and length") {
    val p = plan("""select(.user.country == "FR") | .name""")
    assertEquals(p.instrs, List(ReadStr(0, 1), Skip(SInt), ReadStr(2, 3)))
    val f = Frame(p.slots)
    f.bytes = Binary.encode(john, event)
    p.read(f)
    def str(off: Int, len: Int) = String(f.bytes, f.nums(off).toInt, f.nums(len).toInt, "UTF-8")
    assertEquals((str(0, 1), str(2, 3)), ("john", "FR"))
  }

  test("a record gone through is read to its end when something follows it") {
    // .user.age, then .comment: the country is skipped, so that the cursor reaches the comment
    assertEquals(plan("select(.user.age > 1) | .comment").instrs, List(Skip(SString), ReadInt(0), Skip(SString), ReadStr(1, 2)))
    assertEquals(plan(".user | .country").instrs, List(Skip(SString), Skip(SInt), ReadStr(0, 1)))
  }

  test("fields the schema does not have, fields of a string: null") {
    assertEquals(plan(".nope.x").values, Map(List("nope", "x") -> PNull))
    assertEquals(plan(".name.x").values, Map(List("name", "x") -> PNull))
    assertEquals(plan(".name.x").instrs, List(Skip(SString)))
  }

  test("a record used whole gets no value: the program is left to the interpreter") {
    assertEquals(plan(".user").values, Map.empty[Path, PVal])
  }
