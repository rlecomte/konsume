package buildyourown

import Schema.*
import Value.*

/** The schema and the records the tests and the pages use. */
object Examples:

  val event: Schema = SRecord(List(
    "name"    -> SString,
    "user"    -> SRecord(List("age" -> SInt, "country" -> SString)),
    "comment" -> SString
  ))

  def record(name: String, age: Int, country: String, comment: String = "-"): Value =
    Obj(List("name" -> Str(name), "user" -> Obj(List("age" -> Num(age), "country" -> Str(country))), "comment" -> Str(comment)))

  val john: Value = record("john", 42, "FR", "a long comment nobody reads")
  val jane: Value = record("jane", 17, "US")
