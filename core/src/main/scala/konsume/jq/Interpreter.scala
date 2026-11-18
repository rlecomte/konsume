package konsume.jq

import io.circe.Json

/** Reference implementation: a tree-walking interpreter in continuation-passing style.
  *
  * This is what the staged compiler must agree with, and the baseline it must beat: every
  * record pays for the dispatch on the AST nodes and for the megamorphic continuations.
  */
object Interpreter:

  def eval(program: Jq, input: Json)(k: Json => Unit): Unit = program match
    case Jq.Identity          => k(input)
    case Jq.Literal(v)        => k(v)
    case Jq.Field(target, n)  => eval(target, input)(j => k(Runtime.field(j, n)))
    case Jq.Index(target, i)  => eval(target, input)(j => k(Runtime.index(j, i)))
    case Jq.Iterate(target)   => eval(target, input)(j => Runtime.iterate(j).foreach(k))
    case Jq.Pipe(left, right) => eval(left, input)(j => eval(right, j)(k))
    case Jq.Compare(op, l, r) =>
      // jq iterates over the right operand in the outer loop
      eval(r, input)(b => eval(l, input)(a => k(Runtime.bool(Runtime.compareOp(op, a, b)))))
    case Jq.And(l, r) =>
      eval(l, input) { a =>
        if !Runtime.truthy(a) then k(Json.False)
        else eval(r, input)(b => k(Runtime.bool(Runtime.truthy(b))))
      }
    case Jq.Or(l, r) =>
      eval(l, input) { a =>
        if Runtime.truthy(a) then k(Json.True)
        else eval(r, input)(b => k(Runtime.bool(Runtime.truthy(b))))
      }
    case Jq.Not          => k(Runtime.bool(!Runtime.truthy(input)))
    case Jq.Select(cond) => eval(cond, input)(c => if Runtime.truthy(c) then k(input))
    case Jq.Empty        => ()
    case Jq.Length       => k(Runtime.length(input))
    case Jq.Test(re)     => eval(re, input)(r => k(Runtime.bool(Runtime.test(input, Runtime.compileRegex(r)))))
    case Jq.StartsWith(p) => eval(p, input)(s => k(Runtime.bool(Runtime.startsWith(input, s))))
    case Jq.EndsWith(s)   => eval(s, input)(x => k(Runtime.bool(Runtime.endsWith(input, x))))

  def all(program: Jq, input: Json): Vector[Json] =
    val out = Vector.newBuilder[Json]
    eval(program, input)(out += _)
    out.result()
