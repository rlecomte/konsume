# 7. From the small konsume to konsume

Every idea of the small konsume is in konsume, under the same name or nearly. This chapter maps one
onto the other, then goes through what konsume adds, and why.

Paths below are under `core/src/main/scala/konsume/`.

## Where each idea lives

| Small konsume | konsume | What changes |
|---|---|---|
| `Expr` | `jq/Ast.scala` | more constructs: `and`, `or`, `not`, `[i]`, `.[]`, `length`, `test`, `startswith`... |
| `Parser` | `jq/Parser.scala` | a tokenizer first, then the same recursive descent |
| `Value` | `io.circe.Json` | the reference works on circe's JSON view |
| `Binary.decode` | `avro/AvroDecoder.scala` + `AvroJson.scala`, `json/JsonDecoder.scala` | Apache Avro's reader, then the JSON view; circe for JSON |
| `Interpreter` | `jq/Interpreter.scala`, `jq/Runtime.scala` | jq's semantics: type order, errors, several outputs everywhere |
| `Reference`, `Output` | `Pipeline.scala` (`interpreted`) | records in and out, a batch at a time |
| `Demand` | `planned/Demand.scala`, `planned/Syntax.scala` | a tree of paths; a path's use: its value, its `length`, or walked with `.[]` |
| `Frame` | `planned/Frame.scala` | longs, references, registers, the output buffer; one per thread |
| `PVal` | `planned/PVal.scala` | more cases: `PChoice` (below), `PEncoded` (a value used whole, left in the bytes) |
| `Plan`, `Instr`, `Plan.read` | `avro/AvroPlan.scala`, `avro/AvroPlanned.scala` | unions, arrays, maps, enums, floats, values used whole; instructions turned into closures |
| (no schema) | `json/JsonPlanned.scala`, `json/JsonCursor.scala` | the reader for JSON (below) |
| `Program`, `Cond` | `planned/Program.scala` | a fast path or a fallback per node, not per program |
| `Planned.Engine` | `planned/Planned.scala`, `Pipeline.scala` | one program per schema id, built on first sight |
| `Planned.write` | `planned/Program.scala` (`output`), `planned/JsonOut.scala`, `avro/AvroWriter.scala` | a reused byte buffer; records, arrays, maps written from their bytes |
| `OracleSuite` | `AvroPlannedSuite`, `JsonPlannedSuite` (tests) | random Avro schemas, JSON documents, invalid payloads |

## What konsume adds

**JSON has no schema.** The reader cannot know in advance where `.user.age` is, nor what it is.
It scans the payload once, compares each key with the expected names as bytes, and skips the
values of the others. What it finds at a path is recorded as its **kind** (null, false, true,
number, string, string with escapes, array, object) in a slot, plus its position or its number.
The value of a path is then a **choice on the kind**, `PChoice(kindSlot, branches)`, and every
fast path of `Program` is written once per branch: the string branch of a JSON value gets what
an Avro string gets. JSON is also validated whole, as the reference does, so the reader cannot
stop early as the Avro one does.

**Avro unions.** `["null", "string"]` is a union: the record holds the index of the branch,
then its value. The plan reads the index into a slot and has instructions per branch; the value
of the path is again a `PChoice`, on the index.

**Arrays.** `.items[0]` is a path: the reader walks the array up to that element
([array-index](../docs/optimizations/array-index.md)). `.items | length` counts the elements
while skipping them ([length-without-value](../docs/optimizations/length-without-value.md)).
`.items[] | select(. > 50)` walks the array element by element while the program runs, each
element read into the same slots ([walking-arrays](../docs/optimizations/walking-arrays.md)).

**Values used whole.** The small konsume leaves `.user` to the interpreter. konsume records the
offset of a record, an array or a map used whole, writes it as JSON straight from its bytes when
it is emitted, and decodes it only when it is used otherwise.

**A fallback per node.** The small konsume leaves a whole program to the interpreter as soon as one
part has no fast path. konsume decides node by node: a node without a fast path works on the
JSON view of its inputs (decoded from the slots when needed, once per record), the others keep
theirs. A program is never slower than its slowest part.

**Threads and batches.** One frame per thread, found once per batch (a lookup per record cost up
to 40 % of the time: [profiling-fixes](../docs/optimizations/profiling-fixes.md)); one program
per Avro schema id, the last one kept for the next record.

**Exact outputs.** The outputs are compared byte for byte with the reference: doubles written
as Java writes them, escapes of control characters and of U+007F to U+009F, invalid UTF-8 replaced
as circe does, JSON objects copied without their spaces
([encoded-values](../docs/optimizations/encoded-values.md),
[output-from-slots](../docs/optimizations/output-from-slots.md)). Two differences are accepted and
documented: the order of the keys of an Avro map, and JSON objects with a key seen twice.

**Strings without decoding.** `test("^jo")` runs a regex on a buffer of chars decoded from the
bytes and reused ([regex-on-bytes](../docs/optimizations/regex-on-bytes.md)); `startswith` and
`==` compare UTF-8 bytes in place, as the small konsume does.

## Measuring

The small konsume is about ideas; konsume is about measured costs. `bench/run.sh` measures a program
of each shape, in time and in bytes allocated per record, and every change is compared with the
previous measure ([benchmarks](../docs/benchmarks.md)). On the benchmark's events, the planned
engine allocates nothing beyond the records it emits, and is 2 to 10 times faster than the
interpreter.

## Where to start reading konsume

1. `planned/Planned.scala`: the engine in thirty lines.
2. `avro/AvroPlan.scala`: the reading plan, with unions and arrays.
3. `planned/Program.scala`: the closures, one fast path after the other.
4. `json/JsonPlanned.scala`: the same without a schema.
5. `docs/optimizations/README.md`: each fast path, why it is right, and what it saves.
