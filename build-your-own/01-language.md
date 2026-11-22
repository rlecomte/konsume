# 1. The language

Before running a program, we need it in a form code can work on: a tree. This chapter defines
mini-jq, its tree, and the parser that builds the tree from text.

## mini-jq

```
.                              the input itself
.name                          a field of the input
.user.age                      a field of a field
30   "FR"                      literals: integers, strings
.age > 30   .country == "FR"   comparisons: true or false
select(.age > 30)              the input if the condition is true, nothing otherwise
select(.age > 30) | .name      the right side on each output of the left side
```

A program takes one input and gives **zero, one or more outputs**: `select` gives none when its
condition is false. Run on a stream of records, a program filters (`select(...)`), projects
(`.name`), or both. Two conditions are two `select`s: `select(.age > 30) | select(.country ==
"FR")`.

konsume's jq has much more (`and`, `or`, arrays, `.[]`, `length`, `test`...), but each piece is
built on the ideas this subset shows.

## Values

`Value.scala`: what a record decodes to, and what a comparison gives.

```scala
enum Value:
  case Null
  case Bool(b: Boolean)
  case Num(n: Long)
  case Str(s: String)
  case Obj(fields: List[(String, Value)])
```

`render` writes a value as JSON text: `Obj(List("age" -> Num(30)))` is `{"age":30}`.

## The tree

`Expr.scala`: one case per construct.

```scala
enum Expr:
  case Identity                                  // .
  case Field(target: Expr, name: String)         // .name, applied to the output of target
  case Literal(value: Value)                     // 30, "FR"
  case Compare(op: Op, left: Expr, right: Expr)  // ==, >
  case Select(cond: Expr)
  case Pipe(left: Expr, right: Expr)             // left | right
```

A path is a chain of `Field`s ending with `Identity`: `.user.age` is
`Field(Field(Identity, "user"), "age")`. The same shape as in konsume (`jq/Ast.scala`).

## The parser

`Parser.scala` turns text into an `Expr` by **recursive descent**: one method per level of the
grammar, from the loosest operator to the tightest.

```
pipe    := compare ("|" pipe)?
compare := term (("==" | ">") term)?
term    := path | number | string | "select" "(" pipe ")"
```

Each method parses what belongs to its level and asks the next one for the pieces. On
`select(.a > 1) | .b`:

1. `pipe` asks `compare` for its left side, which asks `term`;
2. `term` sees `select`, reads `(`, and parses the condition by calling `pipe` again (that is
   the recursion): `.a > 1`, then reads `)`;
3. back in `compare`: no `==` nor `>` follows `select(...)`;
4. back in `pipe`: a `|` follows, so the right side is another `pipe`: `.b`.

Result: `Pipe(Select(Compare(Gt, .a, 1)), .b)`.

The order of the levels gives the precedence: `.a > 1 | .b` is `(.a > 1) | .b`. And `|` is
right-associative because `pipe` calls itself for its right side: `a | b | c` is `a | (b | c)`.

## Tests

`ParserSuite`: paths, literals, comparisons, precedence, associativity, and texts that must be
rejected.

Next: [the interpreter](02-interpreter.md).
