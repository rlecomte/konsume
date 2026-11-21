# Optimisations of the planned engine

The planned engine reads only the paths a program uses into slots, and runs the program as
closures specialised on what it finds there (see the README, "How it works"). Wherever it does
not know how to work on a slot, it falls back to the JSON view of the value (`io.circe.Json`, a
tree of objects), through `Program.materialize`. These pages describe, one by one, the fast paths
added to avoid that fallback on common shapes of programs.

| Page | Shape of program | Commit |
|---|---|---|
| [Output written from the slots](output-from-slots.md) | `.user.country`, `select(...) \| .name` | `7efbd03` |
| [Two paths compared in place](compare-two-paths.md) | `select(.name == .user.country)` | `7efbd03` |
| [`length` without the value](length-without-value.md) | `select(.items \| length > 2)` | `7efbd03` |
| [Array indexes in paths](array-index.md) | `.items[0]`, `.rs[1].b` | `e0e102c` |
| [`test()` without allocating](regex-on-bytes.md) | `select(.name \| test("^jo"))` | `10975a2` |
| [`startswith` / `endswith` on JSON strings](affix-on-json-strings.md) | `select(.name \| startswith("jo"))` | `2697b5e` |
| [Two allocations per record, found by profiling](profiling-fixes.md) | every Avro program | `7efbd03` |
| [Arrays walked element by element](walking-arrays.md) | `.items[] \| select(. > 50)`, `.rs[] \| .b` | `46b7abe` |
| [Avro values written from their bytes](encoded-values.md) | `.user` (a record, an array, a map) | |

## How it is measured

`ShapesBench` (bench) runs one program per shape on the same 10k events, Avro (~19 bytes) and
JSON (~65 bytes), with JMH and `-prof gc`:

```
sbt 'bench/Jmh/run -prof gc -p engine=Planned konsume.bench.ShapesBench'
```

Two numbers per program: µs per batch of 10k records, and bytes allocated per record. One fork:
take the times as ±10 to 20 %; the allocations are stable to the byte.

## The floor

A program that emits something other than the record unchanged has to allocate, whatever the
engine: a new `Record` (~48 bytes: object header, topic, partition, offset, timestamp, key,
value) and the bytes of its value (~24 bytes for a small value such as `"FR"`). About **75 bytes
per record emitted** is the floor; what is measured above it is work that can be removed. A filter
that emits the record unchanged, or a program that emits nothing, has a floor of 0. The few bytes
per record left on such programs are the output buffer growing.

## Where things stand

Planned engine, bytes allocated per record:

| Program | Avro | JSON |
|---|---:|---:|
| `select(.user.age > 30 and .user.country == "FR")` | 1.7 | 1.7 |
| `select(.name == .user.country)` | 0 | 0 |
| `select(.items \| length > 2)` | 3 | 3 |
| `select(.name \| test("^jo"))` | 7 | 7 |
| `select(.name \| startswith("jo"))` | 7 | 7 |
| `.user.country`, `.user.age`, `.items[0]` | 85 (floor) | 85 (floor) |
| `.items[] \| select(. > 50)` | 83 (floor) | 83 (floor) |
| `.user` | 108 (floor) | 1 115 |

`.user` in JSON still builds the JSON view of the object.

## Principles that held for all of them

- **Decide at build time, not per record.** Each fast path is chosen when the program is turned
  into closures, from the `PVal` of the path (what the reader will find there); running a record
  only reads slots.
- **A choice is a dispatch.** A JSON value has no schema: its `PVal` is a `PChoice` on the kind
  found (null, false, true, number, string, string with escapes, array, object). Every fast path
  recurses into the branches of a `PChoice`, so that the string branch of a JSON value gets the
  same treatment as an Avro string.
- **Same result as the reference, always.** Each fast path covers the cases where it gives the
  very same result as the JSON view, and falls back to it for anything else (escapes, ill-formed
  UTF-8, a type with no fast path). The tests compare every program with the interpreter, and
  re-encoded outputs byte for byte.
- **Check the test can fail.** Each new test was run once against a deliberately broken
  implementation, to make sure it catches the bug it is meant to catch.
- **Measure the allocation, then profile.** When an allocation stayed where none was expected,
  JFR (`jdk.ObjectAllocationSample`) found it; twice it was not in the new code.
