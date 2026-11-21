# Output written from the slots

**Programs:** a path emitted as the output: `.user.country`, `.user.age`,
`select(.user.age > 30) | .name`, `.items[0]`.

## Before

Emitting a value went through its JSON view, for every record. For `.user.country`:

| Step | Object created | Needed? |
|---|---|---|
| decode the string of the slot | `String "FR"` | no |
| wrap it | `JString` | no |
| `noSpaces` | `String "\"FR\""` and a `StringBuilder` | no |
| `getBytes` | `byte[]` of 4 bytes | yes: the output |
| `record.copy(value = ...)` | a new `Record` | yes: the output |

## Idea

When the program ends with a path (the `Sink` it passes its value to is `emit`), write the output
bytes directly from the slots, without building a `Json`. Only for values where that gives exactly
the bytes `noSpaces` would write; anything else still goes through `Json`.

## How

`Program.atPath(path, k)` (shared by fields and array indexes): if `k eq emit`, the node becomes
`f.out += f.record.copy(value = w(f))`, where `w = output(penv(path))` is chosen at build time.

`Program.output(v: PVal): Frame => Array[Byte]`:

| `PVal` | Bytes written |
|---|---|
| `PStatic(j)` | `j.noSpaces`, computed once, cloned per record |
| `PLong(s)` | the decimal digits, written by hand (`Ascii.long`), as `Long.toString` |
| `PBoolean(s)` | `true` / `false`, precomputed |
| `PEnum(s, symbols)` | `"SYMBOL"` per symbol, precomputed |
| `PString(off, len)` | `"` + the bytes + `"`, when every byte is printable ASCII other than `"` and `\` (`Ascii.plain`); else through `Json` |
| `PNumber(slot, start, len)` (JSON) | the number as written in the payload |
| `PChoice(s, branches)` | the branch chosen by slot `s` |
| anything else (`PDouble`, `PText`, `PSlice`, `PJson`...) | through `Json` |

## Why it gives the same bytes

- Strings: `noSpaces` escapes `"`, `\` and control characters, and writes everything else as is.
  Restricting the copy to printable ASCII without `"` nor `\` (0x20 to 0x7E) leaves out every
  character that could be escaped; non-ASCII strings go through `Json` (they are not escaped
  either, but invalid UTF-8 would be replaced by U+FFFD).
- JSON numbers: circe keeps the text of a parsed number (`1.0`, `1e2`, `-0`,
  `12345678901234567890` are written back as they were). A first version special-cased `-0`,
  wrongly: the byte-for-byte test showed circe keeps it too.
- Avro longs: `Json.fromLong(x).noSpaces` is `Long.toString(x)`.

## Tests

- Both suites compare re-encoded outputs **byte for byte** with the interpreter (they used to
  compare what the JSON means).
- `JsonPlannedSuite`, "outputs written from the payload are byte for byte those of the
  reference": numbers in every form (`-0`, `1.0`, `1E+2`, `-1.5e-3`, 18 to 20 digits, `0.1000`),
  strings with escapes, non-ASCII, emoji, DEL (0x7F), `\/`, booleans, null, arrays, objects.
- Checked to fail when strings are quoted without the ASCII check.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `.user.country` | 500 µs, 219 B | 310 µs, **85 B** | 1 574 µs, 285 B | 1 263 µs, **85 B** |
| `.user.age` | 387 µs, 245 B | 279 µs, **85 B** | 1 777 µs, 501 B | 1 192 µs, **85 B** |
| `select(.user.age > 30) \| .name` | 498 µs, 177 B | 294 µs, **52 B** | 1 689 µs, 177 B | 1 335 µs, **52 B** |

85 bytes is the floor (the new `Record` and its value). The filter-then-field emits for about
60 % of the records, hence 52.

## Limits

- Only a path emitted as the whole output. `{name: .name}` (not supported by the parser yet) or a
  length emitted (`.items | length`) still go through `Json`.
- Doubles (`PDouble`) go through `Json`: circe's formatting of doubles is not reproduced.
