package buildyourown

import Expr.*
import Value.*

/** Runs a program on a value, case by case on the tree: the simplest engine there is, and the
  * reference the other one is checked against.
  */
object Interpreter:

  /** The outputs of `program` on `input`: zero, one or more. */
  def eval(program: Expr, input: Value): List[Value] = program match
    case Identity           => List(input)
    case Field(target, name) => eval(target, input).map(field(_, name))
    case Literal(v)         => List(v)
    case Compare(op, l, r)  => for a <- eval(l, input); b <- eval(r, input) yield Bool(compare(op, a, b))
    case Select(cond)       => eval(cond, input).filter(truthy).map(_ => input)
    case Pipe(l, r)         => eval(l, input).flatMap(eval(r, _))

  /** `.name`: null when there is no such field, or when the value is not an object (jq would
    * raise an error there; the small konsume keeps to null).
    */
  def field(v: Value, name: String): Value = v match
    case Obj(fields) => fields.collectFirst { case (`name`, x) => x }.getOrElse(Null)
    case _           => Null

  /** `==` on any values; `>` between two numbers or two strings, false otherwise. */
  def compare(op: Op, a: Value, b: Value): Boolean = op match
    case Op.Eq => a == b
    case Op.Gt =>
      (a, b) match
        case (Num(x), Num(y)) => x > y
        case (Str(x), Str(y)) => x > y
        case _                => false

  /** As in jq: only false and null are false. */
  def truthy(v: Value): Boolean = v != Null && v != Bool(false)
