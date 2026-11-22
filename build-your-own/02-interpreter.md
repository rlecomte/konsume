# 2. The interpreter

The simplest way to run a program on a record: decode the whole record into a tree of values,
then walk the program's tree on it. This chapter builds that engine. Everything after is about
doing the same thing faster, and this engine is what "the same thing" means.

## The format: mini-Avro

`Schema.scala`, `Binary.scala`. A record is written as its values one after the other, in the
order of its schema, with nothing else: no field names, no types, no separators.

```
int     4 bytes, big endian
string  its length on 1 byte, then its UTF-8 bytes
record  its fields, in the order of the schema
```

With the schema `{name: string, user: {age: int}}`, the record `{name: "jo", user: {age: 30}}`
is 7 bytes:

```
02 6A 6F     00 00 00 1E
"jo"         30
```

The bytes alone mean nothing: only the schema says that the first byte is a length, and that
an int follows the string. That is how Avro works (in konsume, the schema comes from a registry,
found by an id at the start of each record).

`Binary.decode(bytes, schema)` reads the whole record: a `Reader` moves a position through the
bytes, and builds a `Value` for each field.

## Running the tree

`Interpreter.eval(program, input): List[Value]`: one case per kind of node. A list, because a
program gives zero, one or more outputs.

```scala
case Identity            => List(input)
case Field(target, name) => eval(target, input).map(field(_, name))
case Literal(v)          => List(v)
case Compare(op, l, r)   => for a <- eval(l, input); b <- eval(r, input) yield Bool(compare(op, a, b))
case Select(cond)        => eval(cond, input).filter(truthy).map(_ => input)
case Pipe(l, r)          => eval(l, input).flatMap(eval(r, _))
```

`select(.user.age > 30)` on John: `Select` evaluates its condition, a `Compare` whose left side
is `.user.age` (the field `age` of the field `user` of the input: 42) and right side `30`; the
result is `true`, so `Select` outputs its input, the record.

The rules, simplified from jq:

- `.name` gives null when there is no such field, or when the value is not an object (jq raises
  an error on `"jo" | .x`; konsume does too);
- `==` compares any values; `>` is true only between two numbers or two strings;
- only `false` and `null` are false.

## Outputs

`Reference.run(program, schema, bytes)`: decode, evaluate, then turn each output into what is
emitted.

```scala
enum Output:
  case Original          // the record itself, unchanged: emitted with its own bytes
  case Text(json: String) // any other value, written as JSON
```

A filter (`select(...)`) outputs the very record it was given: konsume then emits the record as
it came, without encoding anything (`v eq record`: the same object, not an equal one). Any other
output (`.user`, `.age > 30`) is written as JSON.

## What it costs

`select(.user.age > 30)` on John (`{name: "john", user: {age: 42, country: "FR"}, comment:
"a long comment nobody reads"}`, 40 bytes) uses one integer. To get it, the interpreter:

- reads all 40 bytes, the comment included;
- builds 6 values (2 objects, 3 strings, 1 number), 3 Java strings (one of them the comment), 5
  pairs and 5 list cells for the fields;
- then, while evaluating, a list per node of the program and a boolean.

All of it is thrown away once the record is filtered. On a stream of millions of records, this
allocation is most of the cost: in konsume, the same engine allocates about 2 KB per record on
the benchmark's events, against a few bytes for the planned engine.

The next chapters remove it, in four steps:

1. find, from the program alone, which paths it uses (here, `.user.age` only);
2. read only those, into slots (a `Long` array), skipping the rest of the bytes;
3. turn the program into closures that compare slots, once, before any record;
4. write the outputs from the bytes.

## Tests

`InterpreterSuite`: the bytes of a record and back; filters, projections, comparisons, missing
fields, on the records of `Examples.scala` (the schema the next chapters use too).

Next: [the demand](03-demand.md).
