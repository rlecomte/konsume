# `startswith` / `endswith` on JSON strings

**Programs:** `select(.name | startswith("jo"))`, `select(.name | endswith("ne"))`.

## Before

`startswith` and `endswith` with a literal were already compared as bytes in place, but only when
the value of the path was a bare `PString`:

```scala
o match
  case Operand.At(PString(off, len)) => /* compare the bytes in place */
  case _                             => /* through Json */
```

In Avro the schema says `name` is a string: its value is a `PString`, the fast path applied. In
JSON there is no schema: `name` may be a string, a number, null, missing... Its value is a choice
on the kind found by the reader:

```
PChoice(kind slot, [ null, false, true, PNumber, PString, PText, array, object ])
```

It did not match `PString`, so every JSON record went through `Json`: a `String`, a `JString`, then
`Runtime.startsWith`. 77 bytes per record.

## How

`Program.affixAt(v, j, lit, atEnd)`, built like `testAt` for [regexes](regex-on-bytes.md):

- `PString(off, len)`: `bytesAt(bytes, off, len, lit, atEnd)`: is `lit` (the UTF-8 bytes of the
  literal, computed once) a prefix (or suffix) of the bytes;
- `PChoice(s, branches)`: one closure per branch, chosen by slot `s` at run time;
- anything else: on its JSON view (a string with escapes; for a number or null, jq's error).

On `{"name":"john"}`: the reader writes kind 4 (string), start 9, length 4; the closure reads kind
4, calls branch 4, which compares bytes 9 and 10 with `6A 6F` (`jo`). No object created.

## Why comparing bytes is enough

In UTF-8, "the text starts with `jo`" is "the bytes start with the bytes of `jo`": each character
has a single encoding, and a character cannot start in the middle of another. The same principle as
`== "FR"`, used since the first version of the engine.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `select(.name \| startswith("jo"))` | 189 µs, 6.6 B | 190 µs, 6.6 B | 1 164 µs, 77 B | 1 138 µs, **6.6 B** |

In JSON the time is dominated by the scan of the payload.

## Limits

Bytes are not decoded: on ill-formed UTF-8 (`E9 41`, Latin-1), the reference sees `�A`, the engine
the raw bytes. They differ only if the literal itself contains U+FFFD. This was already the case
for Avro strings, and invalid UTF-8 in JSON is a known limit (README).
