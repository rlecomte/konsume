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

jq programs see Avro values as JSON (`avro/AvroJson`): unions unwrapped (`null` or `"x"`, not
`{"string":"x"}`), enums as strings, maps and records as objects, `bytes`/`fixed` as base64 strings,
`decimal` as numbers, other logical types as their underlying value (`timestamp-millis`: a number of
milliseconds), NaN and infinities as `null`. Values are read with their writer schema: a field missing
from an older schema is `null`.

## How it works

- `jq/Parser`: jq subset → `Jq` AST.
- `jq/Interpreter`: tree-walking interpreter, the reference semantics (`Engine.Interpreted`).
- `jq/Runtime`: jq semantics shared by both engines.
- `planned/`: the planned engine (`Engine.Planned`, the default), shared by both formats.
  `Demand` finds the paths a program uses on its syntax tree; a format reader fills the slots of
  a `Frame` and describes what it found (`PVal`); `Program` turns the program into closures once,
  with comparisons specialised on that (strings compared as UTF-8 bytes in place, `startswith`
  and `endswith` too, union branches and JSON kinds resolved by their index), two paths
  compared in place, `length` of an array counted while reading, and a path emitted as the
  output written from the payload, byte for byte what the reference writes. One frame per
  thread, reused: running a program allocates nothing of its own. Building a program takes well
  under a millisecond.
- `avro/`: Confluent framing, the Avro cursor, the reference decoder (`AvroDecoder`, then
  `AvroJson`), and the planned reader. `AvroPlan`: for one writer schema and the paths a program
  uses (`.user.age`), the reading plan: fields in schema order, the needed ones into typed slots
  (a `long`, a `double`, the offset and length of a UTF-8 string, a union index...), the others
  skipped without being decoded, stop after the last needed one. Values used as a whole
  (records, arrays, maps, bytes) are left in the payload: written as JSON straight from their
  bytes when emitted (`AvroWriter`), decoded into their JSON view only when used otherwise.
  Arrays iterated (`.items[]`) are walked element by element. `AvroPlanned` runs this plan with
  a small interpreter; one program per writer schema id, built on first sight of the id.
- `json/`: the JSON cursor, the reference decoder (circe), and the planned reader. `JsonPlanned`:
  keys come in any order, so each object of interest is scanned and its keys compared as bytes
  with the expected names; the values of the others are skipped without being decoded. A used
  value is kept as its kind, plus a double or its position in the payload; an object or an array
  emitted is copied from the payload without its spaces. The whole payload is
  read and validated (the tests check it is rejected exactly when circe rejects it); duplicate
  keys: the last one wins, as in jq.

Known limits:
- Avro: reading stops after the last field the program needs, so a payload corrupted or
  truncated after that point is accepted (the reference decoder rejects it); a value skipped is
  checked for its structure only (an invalid enum index in it is not seen);
- Avro: an emitted map has its keys in the order of the payload; the reference has them in the
  order of a `HashMap` (Apache's reader), so the JSON is the same but the bytes may differ;
- JSON: an object emitted with a key seen twice is copied as it comes; the reference keeps one
  key, with the last value (the same JSON for a reader that keeps the last value, as jq does);
- JSON: invalid UTF-8 is not detected, and strings without escapes are compared as bytes, so the
  result on invalid UTF-8 may differ from the reference.

The fast paths of the planned engine (output written from the slots, two paths compared in
place, `length` without the value, array indexes in paths, regexes without allocating...) are
described one by one, with their measurements: [docs/optimizations](docs/optimizations/README.md).

An earlier version compiled the program to bytecode at runtime (Scala 3 staging): about 2x
faster, but with a compiler in memory and a heap leak. What was measured and learnt:
[docs/staged-engine.md](docs/staged-engine.md).

## Benchmark

`sbt 'bench/Jmh/run -prof gc konsume.bench.ShapesBench'`: one program per shape, the same 10k
events in Avro (~19 bytes) and JSON (~65 bytes), JDK 25, µs per batch (bytes allocated per
record). An output other than the record unchanged costs ~75 bytes whatever the engine (a new
`Record` and its value).

| program                                            | Avro planned   | Avro interpreted | JSON planned    | JSON interpreted |
|----------------------------------------------------|---------------:|-----------------:|----------------:|-----------------:|
| `select(.user.age > 30 and .user.country == "FR")` | **336** (1.7)  | 3 014 (2 251)    | **1 391** (1.7) | 3 075 (2 033)    |
| `select(.name \| test("^jo"))`                     | **330** (7)    | 3 222 (2 662)    | **1 263** (7)   | 3 697 (2 489)    |
| `select(.name \| startswith("jo"))`                | **190** (7)    |                  | **1 138** (7)   |                  |
| `select(.name == .user.country)`                   | **318** (0)    | 2 656 (2 031)    | **1 303** (0)   | 2 952 (1 886)    |
| `select(.items \| length > 2)`                     | **517** (3)    | 2 476 (2 011)    | **1 214** (3)   | 2 873 (1 909)    |
| `.user.country`                                    | **310** (85)   | 2 525 (2 156)    | **1 263** (85)  | 3 015 (2 095)    |
| `.user.age`                                        | **279** (85)   | 2 799 (2 124)    | **1 192** (85)  | 3 024 (2 099)    |
| `select(.user.age > 30) \| .name`                  | **294** (52)   | 3 174 (2 269)    | **1 335** (52)  | 3 866 (2 111)    |
| `.user`                                            | **527** (108)  | 3 936 (2 458)    | **1 631** (108) | 3 870 (2 245)    |
| `.items[0]`                                        | **448** (85)   | 2 807 (2 226)    | **1 190** (85)  | 3 035 (2 089)    |
| `.items[] \| select(. > 50)`                       | **568** (83)   | 3 583 (2 537)    | **1 618** (83)  | 3 462 (2 324)    |

Every program is at the floor in both formats: the outputs are written from the payload, no
JSON view is built.

`MemoryProbe` (bench) reports the memory an engine keeps: with 1 000 distinct programs alive,
the planned engine keeps ~1 KiB of heap each and loads no class.
