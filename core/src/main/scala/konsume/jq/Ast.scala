package konsume.jq

import io.circe.Json

enum CmpOp(val symbol: String):
  case Eq  extends CmpOp("==")
  case Neq extends CmpOp("!=")
  case Lt  extends CmpOp("<")
  case Lte extends CmpOp("<=")
  case Gt  extends CmpOp(">")
  case Gte extends CmpOp(">=")

  /** Interprets the result of a three-way comparison. */
  def test(cmp: Int): Boolean = this match
    case Eq  => cmp == 0
    case Neq => cmp != 0
    case Lt  => cmp < 0
    case Lte => cmp <= 0
    case Gt  => cmp > 0
    case Gte => cmp >= 0

  /** `a op b` <=> `b op.flip a` */
  def flip: CmpOp = this match
    case Lt  => Gt
    case Lte => Gte
    case Gt  => Lt
    case Gte => Lte
    case op  => op

/** The supported subset of jq. Every node is a filter: input => 0..n outputs. */
enum Jq:
  case Identity
  case Literal(value: Json)
  case Field(target: Jq, name: String)
  case Index(target: Jq, index: Int)
  case Iterate(target: Jq)
  case Pipe(left: Jq, right: Jq)
  case Compare(op: CmpOp, left: Jq, right: Jq)
  case And(left: Jq, right: Jq)
  case Or(left: Jq, right: Jq)
  case Not
  case Select(cond: Jq)
  case Empty
  case Length
  case Test(regex: Jq)
  case StartsWith(prefix: Jq)
  case EndsWith(suffix: Jq)
