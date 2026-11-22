# 6. Outputs

A program on a stream of records emits records. konsume's rule, kept by the small konsume:

- the record itself, unchanged (a filter: `select(...)`): emitted with its **own bytes**;
- any other value (`.name`, `.user.age`): emitted as **JSON text**.

The first case costs nothing: the input bytes go out as they came, nothing is encoded. For the
second, the planned engine writes the JSON from the slots, without building a `Value`
(`Planned.write`):

```scala
case PInt(s)        => f => f.nums(s).toString                        // 42
case PStr(off, len) => f => Value.quote(String(f.bytes, offset, length))  // "john"
case PNull          => _ => "null"
```

## The same text, exactly

A filter gets through untouched, but a projection is re-encoded: two engines that disagree on a
single character (a space, an escape, `1.0` against `1`) give different records. So the text must
be exactly the one `Value.render` gives: same digits, same quotes, same escapes. The tests compare
the two on values chosen for that: quotes and backslashes in a string, non-ASCII characters,
negative numbers, the smallest int.

The small konsume builds a `String` per output, and a second one for a quoted string. konsume writes
straight into a byte buffer reused from output to output, the digits of a number from the right,
the bytes of a string copied and escaped as they go (`planned/JsonOut.scala`); it also writes
records, arrays and maps used whole, from their bytes, walking their schema (`AvroWriter`), and
copies JSON objects without their spaces. Most of the care is there: doubles written as Java
writes them, escapes of control characters, invalid UTF-8 replaced as circe does
(docs/optimizations/encoded-values.md).

## What is left

On its fast paths, the planned engine of the small konsume builds no `Value`: a filter reads a few
slots and lets the bytes through, a projection writes from the slots. The interpreter remains the
fallback, for comparisons without a fast path and for the programs `Program.compile` leaves.

## How we know it is right

The planned engine is a pile of shortcuts: skip this field, stop there, compare that slot. Each
one is right only if it gives what the interpreter gives, and the interpreter is simple enough to
be trusted: it defines the right answer. So `OracleSuite` generates schemas, records and programs
at random (200 000 runs, in well under a second) and checks that, for every program the planned
engine runs, its outputs are the very same as the interpreter's. That is the reason konsume keeps
its interpreter: it is the reference of its tests (`AvroPlannedSuite`, `JsonPlannedSuite`), and
the fallback of its planned engine.

## Tests

`PlannedSuite`, "projections": ints, strings, missing paths, on records with quotes,
backslashes, non-ASCII characters and extreme numbers, against the reference. `OracleSuite`:
random schemas, records and programs, against the reference.

Next: [from the small konsume to konsume](07-konsume.md).
