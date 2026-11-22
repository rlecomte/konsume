# 3. The demand

The interpreter decodes the whole record because it does not know what the program will look
at. But the program is known before any record arrives: reading its tree is enough to know which
parts of a record it can ever use. That is the **demand**: the set of paths whose values the
program uses.

```
select(.user.age > 30)                    {.user.age}
select(.user.country == "FR") | .name     {.user.country, .name}
.user                                     {.user}
```

For the first program, every record will be read for one integer. The next chapter reads that
integer and nothing else; this one finds it.

## Paths

`Demand.scala`. A path is a list of field names from the root of the record:

```scala
type Path = List[String]   // .user.age is List("user", "age"), the record itself is Nil
```

## The path of an output

Most of mini-jq moves along paths. `pathOf(expr, in)` gives the path of what `expr` outputs,
given the path of its input, when it is known **without running the program**:

```scala
case Identity       => in                             // .: the input
case Field(t, name) => pathOf(t, in).map(_ :+ name)  // .user.age: one more name
case Select(_)      => in                             // select outputs its input, or nothing
case Pipe(l, r)     => pathOf(r, pathOf(l, in))       // r runs on l's output
case _              => None                           // a comparison computes a boolean
```

In `.user | select(.age > 18) | .country`, the input of `select` is `.user`, so its `.age` is
`.user.age`, and the output is `.user.country`. `select` may output nothing, but when it outputs
something, it is its input: the path holds.

## The paths used

A value is **used** when the program compares it, or emits it. `uses(expr, in)` collects them,
case by case:

```scala
case Identity | Literal(_) => Set.empty
case Field(t, _)           => uses(t, in)                        // gone through, not used
case Compare(_, l, r)      => consumed(l, in) ++ consumed(r, in)  // both operands used
case Select(cond)          => consumed(cond, in)
case Pipe(l, r)            => uses(l, in) ++ uses(r, pathOf(l, in))
```

where `consumed(p, in)` is what `p` uses, plus `p`'s own value (its path, if it has one).

Two cases are left out on purpose:

- **A path gone through.** In `.user.age > 30`, `.user` is only a step towards `.user.age`: its
  value is not used. Reading `.user` whole would build the object for nothing.
- **The record emitted as it is.** `select(...)` outputs the record itself: it is emitted with its
  own bytes, it does not have to be read. So `Demand.of` adds the program's output path only when
  it is not the record (`Nil`).

On `select(.user.country == "FR") | .name`: `Select` consumes its condition, a `Compare`, which
consumes `.user.country` and the literal `"FR"` (no path); the output of the program is `.name`.
Demand: `{.user.country, .name}`.

## What it does not catch

A value that does not come from a path, a boolean for example, has no path: `.user.age > 30 |
select(. == true)` uses the boolean, which is computed, not read. Nothing to read for it, so
nothing in the demand; the next chapters leave such programs to the interpreter.

In konsume, `planned/Demand.scala` does the same, with more: the demand is a tree (paths share
their prefixes), and it records how a value is used (whole, or only its `length`, or walked
element by element with `.[]`), so that the reader can do less (count an array instead of
reading it).

## Tests

`DemandSuite`: the demand of programs written by hand, among which the cases above: a path gone
through, the record emitted, a pipe changing the input's path, the record itself compared.

Next: [the reading plan](04-plan.md).
