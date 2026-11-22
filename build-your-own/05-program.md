# 5. The program as closures

After the reading plan, the values a program needs are in slots. What is left is to run the
program on them without going back to `Value`s: comparing `.user.age` with 30 should be comparing
slot 0 with 30, nothing more. And deciding that should happen once, before any record.

## A program is conditions and an output

`select` outputs its input or nothing, and paths compose through `|`. So a program made of paths,
`select`s and pipes comes down to:

> if these conditions hold, emit the value at this path.

```
select(.user.age > 30) | .user | select(.country == "FR") | .name

conditions: .user.age > 30, .user.country == "FR"
output:     .name
```

`Program.scala`:

```scala
final case class Compiled(conds: List[Cond], output: Path)

private def stages(e: Expr, in: Path): Option[Compiled] = e match
  case Identity | Field(_, _)    => Demand.pathOf(e, Some(in)).map(Compiled(Nil, _))   // a path
  case Select(Compare(op, l, r)) => compare(op, l, r, in).map(c => Compiled(List(c), in))
  case Pipe(l, r) =>                                                                  // both
    for a <- stages(l, in); b <- stages(r, a.output) yield Compiled(a.conds ++ b.conds, b.output)
  case _ => None
```

`compile` keeps the result only if the output is the record itself or a path the plan read.
`None` is for everything else: a boolean emitted (`.age > 30`), a record used whole (`.user`), a
pipe inside a condition... The engine then uses the interpreter: correct, just not fast. konsume
decides node by node instead: a node without a fast path goes through the JSON view, the rest of
the program keeps its fast paths.

## A condition is a closure

```scala
trait Cond:
  def apply(f: Frame): Boolean
```

`compare(op, l, r, in)` builds it. The left operand is a path, whose `PVal` the plan gives; the
right one a literal, or another path. The closure is chosen on what they are:

| Operands | The closure | Per record |
|---|---|---|
| a missing path (`PNull`) and a literal | `_ => true` or `_ => false` | nothing: decided now |
| an int (`PInt(s)`) and a number | `f => f.nums(s) > 30` | one array read |
| a string (`PStr(o, l)`) and a string | the slot's bytes against the literal's, in place | no `String` built |
| an int or a string, and a literal of another type | `_ => false` | nothing |
| anything else (two paths, a literal on the left) | `Interpreter.compare` on the decoded values | the slow way |

The last row is the **fallback**: always right, because it is the interpreter's own comparison on
the same values. The fast rows are shortcuts for cases where the result is known to be the same.
Strings are compared as UTF-8 bytes, whose order is the order of the characters (for the
small konsume's ASCII strings; konsume handles the rest).

The closures are built **once**, in `Planned.compile`. Each record then costs: run the plan, call
the conditions, emit. On `select(.user.age > 30)`: skip a string, read an int into slot 0,
compare slot 0 with 30.

## The engine

`Planned.scala`:

```scala
def compile(program: Expr, schema: Schema): Option[Engine] =
  val plan = Plan(schema, Demand.of(program))
  Program(plan.values).compile(program).map(Engine(plan, _))
```

`Engine.run(bytes)` puts the bytes in its frame, runs the plan, the conditions, and emits (next
chapter). One frame per engine: an engine is not to be shared between threads. konsume keeps one
frame per thread, found once per batch.

## Tests

`PlannedSuite`: filters on five records, the planned engine against the reference; every row of
the table above is among them. And six programs the engine leaves to the interpreter.

Next: [outputs](06-outputs.md).
