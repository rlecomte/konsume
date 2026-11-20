# konsume

Scala 3 library to consume Kafka topics, with record processing configured at runtime
(a jq program for now). The program reads only the values it uses, straight from the bytes
(Avro or JSON), without decoding the record.

For now only the pure part exists: `Pipeline[K, V, K2, V2]`, a function from a batch of records
(`Iterable[Record[K, V]]`, what a `poll` returns) to the records to emit.

It works on raw Kafka values (`Array[Byte]`), JSON or Avro (Confluent wire format, writer schemas
from a registry):

```scala
val pipeline = Pipeline
  .forBytes[String](
    ConsumeConfig(Seq("users"), PayloadFormat.Avro, jq = Some("""select(.age > 30)""")),
    schemas = registrySnapshot.get // Int => Option[Schema]; fetching is the consumer's job (Confluent.schemaIds)
  )
  .fold(throw _, identity)
```

A record kept unchanged (a filter) is emitted with its original bytes; a transformed value is
emitted as JSON. Values that cannot be decoded are skipped.

Each jq output gives one record (same metadata and key, value = the output): `select(...)` filters,
`.user` projects, `.items[]` splits a record into several.

## Supported jq

`.`, `.a`, `."a"`, `.["a"]`, `.[0]`, `.[-1]`, `.[]`, `|`, `and`, `or`, `not`, `==`, `!=`, `<`, `<=`,
`>`, `>=`, `select`, `empty`, `length`, `test`, `startswith`, `endswith`, literals
(numbers, strings, `true`, `false`, `null`).

Semantics follow jq 1.7 (type order, `null` propagation, errors, order of outputs for operators that
yield several values); the tests check it against the `jq` binary when it is installed. If the program
fails on a record, that record's remaining outputs are dropped. `test` uses `java.util.regex`, not Oniguruma.

## JSON view of Avro values

jq programs see Avro values as JSON (`format/AvroJson`): unions unwrapped (`null` or `"x"`, not
`{"string":"x"}`), enums as strings, maps and records as objects, `bytes`/`fixed` as base64 strings,
`decimal` as numbers, other logical types as their underlying value (`timestamp-millis`: a number of
milliseconds), NaN and infinities as `null`. Values are read with their writer schema: a field missing
from an older schema is `null`.

## How it works

- `jq/Parser`: jq subset → `Jq` AST.
- `jq/Interpreter`: tree-walking interpreter, the reference semantics (`Engine.Interpreted`).
- `jq/Runtime`: jq semantics shared by both engines.
- `format/AvroPlan`: for one Avro writer schema and the paths a program uses (`.user.age`), the
  reading plan: fields in schema order, the needed ones into typed slots (a `long`, a `double`,
  the offset and length of a UTF-8 string, a union index...), the others skipped without being
  decoded, stop after the last needed one. Values used as a whole (records, arrays, maps, bytes)
  are read into their JSON view.
- `format/Planned`: what both formats share (`Engine.Planned`, the default). The paths a program
  uses are found on its syntax tree; the program is turned into closures once, with comparisons
  specialised on what the reader found (strings compared as UTF-8 bytes in place, `startswith`
  and `endswith` too, union branches and JSON kinds resolved by their index). One frame of slots
  per thread, reused: running a program allocates nothing of its own. Building a program takes
  well under a millisecond.
- `format/AvroPlanned`: runs the reading plan with a small interpreter. One program per writer
  schema id, built on first sight of the id.
- `format/JsonPlanned`: keys come in any order, so each object of interest is scanned and its
  keys compared as bytes with the expected names; the values of the others are skipped without
  being decoded. A used value is kept as its kind, plus a double or its position in the payload.
  The whole payload is read and validated (the tests check it is rejected exactly when circe
  rejects it); duplicate keys: the last one wins, as in jq.

Known limits:
- Avro: reading stops after the last field the program needs, so a payload corrupted or
  truncated after that point is accepted (the reference decoder rejects it);
- JSON: invalid UTF-8 is not detected, and strings without escapes are compared as bytes, so the
  result on invalid UTF-8 may differ from the reference.

An earlier version compiled the program to bytecode at runtime (Scala 3 staging): about 2x
faster, but with a compiler in memory and a heap leak. What was measured and learnt:
[docs/staged-engine.md](docs/staged-engine.md).

## Benchmark

`sbt 'bench/Jmh/run -prof gc konsume.bench.AvroEnginesBench'`: 10k Avro records (~19 bytes), JDK
25, µs per batch (bytes allocated per record).

| program                                            | planned        | interpreted     |
|----------------------------------------------------|---------------:|----------------:|
| `select(.user.age > 30 and .user.country == "FR")` | **348** (1.6)  | 3 047 (2 139)   |
| `select(.name \| test("^jo"))`                     | **464** (220)  | 3 507 (2 702)   |
| `.user.country`                                    | **561** (265)  | 2 888 (2 268)   |
| `.items[] \| select(. > 50)`                       | **940** (493)  | 3 501 (2 488)   |

`sbt 'bench/Jmh/run -prof gc konsume.bench.DecodeBench'`, JSON, same filter as the first line,
10k records (~65 bytes): planned **1 404 µs (1.6 bytes per record)**, decoded with circe then
interpreted 3 097 µs (2 033 bytes per record).

`MemoryProbe` (bench) reports the memory an engine keeps: with 1 000 distinct programs alive,
the planned engine keeps ~1 KiB of heap each and loads no class.
