# Values written from their bytes

**Programs:** an object, an array or a map emitted whole: `.user`, `select(.age > 30) | .user`,
`.tags`, `.attributes`. Avro first, then JSON ([below](#json)).

## Before

A value used as a whole (record, array, map, bytes, fixed) was read into its JSON view while
reading the record (`ReadJson`): a `JObject`, a map, a `JString` or `JNumber` with its `String` for
each field. Emitting it then went through `noSpaces` (a `StringBuilder`, a `String`) and
`getBytes`. Everything but the final array was thrown away at once: 843 bytes per record for
`.user`.

## Idea

Leave the value in the payload, and write its JSON text straight from its bytes when it is
emitted, into a buffer reused from record to record. Decode it into its JSON view only when the
program uses it otherwise (a comparison, a field below it), and then once per record.

## How

### A value left in the payload (`planned/PVal.scala`)

```scala
case PEncoded(pos: Int, cache: Int, rank: Int, value: Encoded)

trait Encoded:
  def json(f: Frame, pos: Int): Json                // its JSON view
  def write(f: Frame, pos: Int, out: JsonOut): Unit // its JSON text, the bytes json(...).noSpaces gives
```

It replaces `PJson` ("the JSON view, already built, is in `refs(slot)`"), which only the Avro reader
produced.

- `AvroPlan.read`: `ReadEncoded(pos, cache)` records the position of the value and empties its
  cache; a `Skip` follows only when something after the value is needed (as for walked arrays).
- `Program.materialize`: decodes on first use, keeps the result in `refs(cache)` for the rest of
  the record (`select(.m.x == 1 and .m.y == 2)` decodes `.m` once). The cache is emptied by
  `ReadEncoded`, at each record and at each element of a walked array.
- `Program.output`: from the cache when the value was already decoded, otherwise `write` into the
  frame's `JsonOut`, then `result()`: the only allocation, the output array.
- Comparisons, `truthy`, `staticRank` use the rank known from the schema, as with `PJson`.
- `AvroFrame.aux`: a second cursor for decoding and writing: the program may be walking an array
  with `cur` at that moment (`.rs[] | .m`).

### `JsonOut` (`planned/JsonOut.scala`)

A growable byte buffer, one per frame, writing what circe's `noSpaces` writes (checked against
circe):

| Value | circe writes | `JsonOut` |
|---|---|---|
| int, long | `Long.toString` | digits written in place |
| float, double | `Float.toString`, `Double.toString`; NaN and infinities `null` | `StringBuilder.append`, reused: same algorithm, no `String` |
| string | `"` and `\` escaped, controls as `\b \t \n \f \r` or `\u00XX` (lower case), and U+007F to U+009F; the rest as is | the UTF-8 bytes copied, these escaped |

- `utf8String` validates the UTF-8 while copying (`Utf8.length`, the table of the regex decoder,
  now shared): on an ill-formed sequence it writes nothing and returns false. The reference
  decodes such a string with U+FFFD replacements; the caller does the same, through circe, for
  that string only.
- `result()` copies the bytes into a new array; a buffer grown beyond 1 MiB for one value is
  dropped after it.
- Writing a double, a float, a long and a string allocates nothing (measured with the thread's
  allocation counter).

### `AvroWriter` (`avro/AvroWriter.scala`)

Built once per schema, as closures, like the reading plans; shared by the threads.

- record: the names written in advance (`{"a":`, then `,"b":`), then each value;
- union: the index read and checked, then the branch;
- array: `[`, block by block, `]`;
- map: `{"key":value,...}`, block by block, the keys in the order of the payload;
- int (read as an int, as the reference does), long, boolean, float, double: `JsonOut`;
- enum: the symbols written in advance; an index out of range throws, the record is skipped;
- string: `utf8String`, through circe when ill-formed;
- bytes, fixed (base64, or a decimal): through circe, for that value only;
- a recursive schema: a reference filled once the record's writer is built.

`Program.output` empties the buffer before writing: a write cut by a decoding error must leave
nothing for the next record.

## Why it gives the same result

Each value is written as `noSpaces` writes the JSON view the reference builds (`AvroJson`): same
numbers (same algorithms), same escapes, same replacements for ill-formed UTF-8, same base64 and
decimals (through circe). A decoding error is raised where the reference raises one, while the
program runs: the record emits nothing (see [walking arrays](walking-arrays.md)).

**One difference, accepted:** the keys of a map. The reference takes the map from Apache's reader,
in a `HashMap`, and gives its keys in hash order; the writer gives them in the order of the
payload. The JSON is the same, the bytes may not be. Making the reference keep the payload's order
needs a custom Apache reader and a change in `AvroJson`; it was judged not worth it. A key seen
twice (Avro writers write maps from maps: never in practice) is written twice; a JSON reader
takes the last value, as the reference has it.

## Tests

- `planned/JsonOutSuite`, against circe: longs (limits, 2 000 random), doubles and floats (limits,
  NaN, infinities, 10 000 random bit patterns), strings (every character up to U+02FF, special
  cases, 3 000 random texts), ill-formed UTF-8 (20 000 random byte sequences: false exactly when
  the JDK's strict decoder rejects them, nothing written then), marks, a huge value.
- `AvroPlannedSuite`, "values emitted whole": a schema with every type, Avro written by hand:
  floats and doubles at their limits, strings with controls, U+007F to U+009F, emoji, ill-formed
  UTF-8 in a value and in a map key, a map with a key seen twice, maps of maps, a map of 70 keys,
  sized blocks, unions, enum, bytes, fixed, decimal, an empty record; three corrupted records,
  first in the batch. Byte for byte for everything without a map of several keys, as JSON values
  (keys in any order) for the others. Each corrupted record is given to the programs that read
  that far: a value skipped is checked for its structure only, nothing after the last value
  read is checked (README).
- The random Avro tests emit records, arrays and maps, byte for byte.
- Checked to fail: without emptying the buffer before a write, without emptying the cache at each
  record, without the fallback for ill-formed UTF-8, without reading the size of a map's block.

## Measured

| Program | Avro before | Avro after |
|---|---|---|
| `.user` | 1 705 µs, 843 B | 527 µs, **108 B** |

108 bytes: the record emitted (~48) and its array (`{"age":30,"country":"FR"}`, 25 bytes and the
array header), plus the output buffer of the batch growing.

## JSON

### Before

An object or an array of a JSON payload emitted whole (`PSlice`: its position in the payload) was
parsed by circe, then written back with `noSpaces`: 1 115 bytes per record for `.user`.

### What circe changes in a slice

Checked by running circe on slices:

| Case | Payload | circe writes | The copy |
|---|---|---|---|
| spaces | `{ "a" : 1 }` | `{"a":1}` | dropped |
| numbers | `1E2`, `1e+2`, `-0`, `0.10`, `1E400` | the same text | copied |
| string without `\` | `"émile 😀"` | the same, U+007F to U+009F escaped | `JsonOut.utf8String` |
| string with `\` | `"\/"`, `"\u0041"`, `"\u001F"` | `"/"`, `"A"`, `"\u001f"` | decoded (`JsonCursor.unescape`), written through circe |
| ill-formed UTF-8 | `61 E9 62` | `"a\uFFFDb"` | the whole slice through circe |
| a key seen twice | `{"a":1,"b":2,"a":3}` | `{"a":3,"b":2}` | **copied as it comes** |

### How

`JsonOut.compact(src, off, len)`: the slice was validated when the record was read, so a walk on
its bytes is enough. Out of a string, spaces are dropped and everything else is copied. At a `"`,
the closing quote is found (skipping `\x`): without a backslash, `utf8String`; with one,
`unescape`, then the string through circe (for that string only). On ill-formed UTF-8, nothing is
written and `compact` answers false: `Program.output` then parses the slice with circe, so that the
U+FFFD replacements are exactly the reference's.

### A difference, accepted

An object with a key seen twice is copied as it comes: `{"a":1,"b":2,"a":3}`, where circe writes
`{"a":3,"b":2}`. A reader that keeps the last value (jq, circe, Jackson by default) reads what the
reference emits; one that keeps the first does not. Detecting them would need the keys of each
object being written kept on a stack; a payload with keys seen twice is ambiguous at its source,
and it was judged not worth it.

### Tests

- `JsonPlannedSuite` compares re-encoded outputs byte for byte, except the ones with a key seen
  twice (found by a small test function), compared by meaning only, as all outputs are.
- "objects and arrays emitted whole": spaces between tokens and in a string, line breaks,
  escapes of every kind (a lone surrogate, an escaped key equal to another), raw U+007F to
  U+009F, numbers of every form, empty and nested objects and arrays, keys seen twice, a
  document with ill-formed UTF-8.
- Checked to fail: strings walked as the rest (spaces dropped in them, escapes copied raw), a
  string with a backslash copied without being decoded, no fallback on ill-formed UTF-8, line
  breaks between tokens copied.

### Measured

| Program | JSON before | JSON after |
|---|---|---|
| `.user` | 3 385 µs, 1 115 B | 1 631 µs, **108 B** |

The time left is mostly reading and validating the payload.

## Limits

- Avro: bytes, fixed and decimals go through circe (base64, `BigDecimal.toString`).
- JSON: strings with escapes go through circe, and slices with ill-formed UTF-8.
- A value used several times is decoded once per record, but a value both emitted and used
  otherwise is written from its JSON view.
- Avro maps: keys in the order of the payload; JSON objects: keys seen twice copied as they come
  (above).
