# `length` without the value

**Programs:** the length of a path: `select(.items | length > 2)`, `select(.name | length > 3)`.

## Before

The demand analysis saw `.items | length` as a use of the whole value of `.items`: the reader read
the array into its JSON view (a `Vector` and a `JNumber` per element), then `Runtime.length` took
its size. 251 bytes per record in Avro, 567 in JSON.

## Idea

Tell the reader that only the length of the path is used. It then counts the elements of an array
while skipping them, into a slot, and the program compares that slot.

## How

### Demand (`planned/Demand.scala`)

- `Demand` has a `length` flag next to `asValue`: "the program takes the length of this path".
  `lengthOnly` = length, but not the value nor any field below it.
- `uses` returns `Use(path, lengthOnly)`: `Jq.Length` on a static input path is a length use,
  everything else a value use.

### Value of the path (`planned/PVal.scala`)

`PSized(count, rank, whole)`: an array or a map whose number of elements is in slot `count`;
`whole` is the value itself when it was read too (JSON), `None` when only counted (Avro). A path
whose value was not read is left out of `Program.materializers`: navigating through it stays
`Undecoded`, and any use of its value would be an internal error (the demand analysis guarantees
there is none).

### Readers

- Avro (`AvroPlan.value`): an array or a map with `lengthOnly` gets `ReadCount(slot, schema)`, run
  by `AvroRuntime.count`, which walks the blocks, skips each element and counts.
- JSON (`JsonPlanned`): a node used by `length` gets a `count` slot; when the value is an array,
  `JsonCursor.countArray` skips and validates it, and counts. The kind, start and length are
  recorded as for any used value (objects are counted by circe, which drops duplicate keys).

### Program (`planned/Program.scala`)

- `operand`: `x | length` on a static path, or `length` with a static input path, is
  `Operand.Len(pv)`.
- `lengthOf(pv)`: the length without the JSON view, when it is known: `PSized` (the count),
  `PString` (code points, counted on the UTF-8 bytes: the bytes that do not start with `10`), null
  (0).
- `compareLength` and `lengthJson`: per branch of a `PChoice`; a branch with no `lengthOf` (a JSON
  object, a number, a boolean, which is an error) goes through `Runtime.length` on its JSON view.
- `boolValue`: `x | cond` where `x` is a static path is `cond` on that path. The parser reads
  `.items | length > 2` as `.items | (length > 2)` (as jq does), which did not reach the fast path
  otherwise.

## Tests

- The random programs of both suites include `$p | length`, `($p) | length` on random schemas and
  documents.
- In JSON, the reading of the payload is unchanged for validation: `countArray` skips with
  `skipValue`, which validates.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `select(.items \| length > 2)` | 662 µs, 251 B | 517 µs, **3 B** | 1 987 µs, 567 B | 1 214 µs, **3 B** |

The first measure after this change still showed 19 bytes in Avro and no gain in JSON: see
[profiling fixes](profiling-fixes.md) for Avro. In JSON, the node of `.items` was materialising
the whole array before handing it to `length`, which ignores it: a JSON node used only by
`length` now has no `whole` value either, so nothing builds it.

## Limits

- Avro records (their number of fields), JSON objects (duplicate keys) and numbers (absolute
  value) go through `Json`.
- A length emitted as the output (`.items | length`) is computed without the array, but written
  through `Json` (`Json.fromLong(n).noSpaces`).
