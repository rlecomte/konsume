package buildyourown

import java.nio.charset.StandardCharsets.UTF_8

import Expr.*
import PVal.*
import Value.*

/** A condition on the slots of a frame, built once, run on every record. */
trait Cond:
  def apply(f: Frame): Boolean

/** A program as the planned engine runs it: if all the conditions hold, the value at `output` is
  * emitted. Most mini-jq programs come down to that: `select` lets its input through, paths
  * compose, so `select(.user.age > 30) | .user | select(.country == "FR") | .name` is "if
  * `.user.age > 30` and `.user.country == "FR"`, emit `.name`".
  */
final case class Compiled(conds: List[Cond], output: Path)

/** Turns a program into a [[Compiled]], on what the reading plan says about each path (`values`).
  * `None` for what it does not handle (a boolean emitted, a record used whole, a pipe inside a
  * condition...): the engine then falls back to the interpreter.
  */
final class Program(values: Map[Path, PVal]):

  /** The output must be the record itself (emitted with its bytes) or a path the plan read. */
  def compile(program: Expr): Option[Compiled] =
    stages(program, Nil).filter(c => c.output.isEmpty || values.contains(c.output))

  private def stages(e: Expr, in: Path): Option[Compiled] = e match
    case Identity | Field(_, _)    => Demand.pathOf(e, Some(in)).map(Compiled(Nil, _))
    case Select(Compare(op, l, r)) => compare(op, l, r, in).map(c => Compiled(List(c), in))
    case Pipe(l, r) =>
      for a <- stages(l, in); b <- stages(r, a.output) yield Compiled(a.conds ++ b.conds, b.output)
    case _ => None

  private def const(b: Boolean): Cond = _ => b

  /** `l op r`, specialised on where the values are: decided here, once, not per record. */
  private def compare(op: Op, l: Expr, r: Expr, in: Path): Option[Cond] =
    for a <- operand(l, in); b <- operand(r, in) yield (a, b) match
      // known now: null and a literal
      case (PNull, Literal(y)) => const(Interpreter.compare(op, Null, y))
      // an int in a slot and a number: compare the slot
      case (PInt(s), Literal(Num(y))) =>
        if op == Op.Eq then f => f.nums(s) == y else f => f.nums(s) > y
      // a string in the bytes and a string: compare the bytes in place
      case (PStr(off, len), Literal(Str(y))) =>
        val lit = y.getBytes(UTF_8)
        if op == Op.Eq then f => bytesCompare(f, off, len, lit) == 0 else f => bytesCompare(f, off, len, lit) > 0
      // an int or a string, and a literal of another type: never equal, not ordered
      case (PInt(_) | PStr(_, _), Literal(_)) => const(false)
      // anything else (two paths, a literal on the left): as the interpreter does, on values
      case _ =>
        val (x, y) = (valueOf(a), valueOf(b))
        f => Interpreter.compare(op, x(f), y(f))

  /** An operand: a literal as it is, a path as what the plan says (none for a record). */
  private def operand(e: Expr, in: Path): Option[PVal | Literal] = e match
    case lit: Literal           => Some(lit)
    case Identity | Field(_, _) => Demand.pathOf(e, Some(in)).flatMap(values.get)
    case _                      => None

  private def valueOf(o: PVal | Literal): Frame => Value = o match
    case Literal(v) => _ => v
    case v: PVal    => Program.materialize(v)

  /** The string at `off`/`len` (slots) against `lit`, byte by byte: UTF-8 bytes are in the order
    * of the characters (the small konsume's strings are ASCII anyway).
    */
  private def bytesCompare(f: Frame, off: Int, len: Int, lit: Array[Byte]): Int =
    val o = f.nums(off).toInt
    java.util.Arrays.compareUnsigned(f.bytes, o, o + f.nums(len).toInt, lit, 0, lit.length)

object Program:

  /** The value of a path as a `Value`: the fallback, for what has no fast path. */
  def materialize(v: PVal): Frame => Value = v match
    case PInt(s)        => f => Num(f.nums(s))
    case PStr(off, len) => f => Str(String(f.bytes, f.nums(off).toInt, f.nums(len).toInt, UTF_8))
    case PNull          => _ => Null
