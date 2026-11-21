# `test()` without allocating

**Programs:** a literal regex on a path: `select(.name | test("^jo"))`.

## Before

```scala
(f, _) => re.matcher(String(f.bytes, f.int(off), f.int(len), UTF_8)).find()
```

Two objects per record: a `String` decoded from the bytes, and a `Matcher` (with its internal
arrays). 192 bytes per record in Avro. In JSON the fast path did not even apply (see below): 224
bytes.

## Idea

`java.util.regex` reads a `CharSequence` (`length()`, `charAt(i)`), not necessarily a `String`,
and a `Matcher` can be reused on a new text with `reset`. So: decode the bytes into a buffer of
chars reused from record to record, show it to a reused `Matcher` as a `CharSequence`.

The bytes cannot be shown as chars directly: Java works in UTF-16, the payload is UTF-8. `é` is two
bytes (`C3 A9`) but one char, `😀` four bytes but two chars. Only ASCII has one byte per char, and
it needs no special case: it is the first branch of the decoder.

## How

### `planned/Regex.scala`

- `Utf8.decode(bytes, off, len, out)`: UTF-8 to UTF-16 into `out`, for well-formed UTF-8 only
  (Unicode, table 3-7). The first byte gives the size of the sequence and the range of the second
  byte; four ranges are narrower than `80`-`BF`, to reject overlong encodings (`E0`, `F0`),
  surrogates (`ED`) and code points above U+10FFFF (`F4`). Returns the number of chars, or -1 at the
  first ill-formed sequence.
- `RegexOnBytes(pattern)`: one per thread and per `test` of the program. It holds the `Matcher` and
  the buffer, and is itself the `CharSequence` over the buffer. `find(bytes, off, len)`:
  - decodes into the buffer, `matcher.reset(this).find()`;
  - grows the buffer (doubling) when a longer string comes; never beyond 64 Ki chars: a longer
    string is decoded into a `String` for its record only, so that one huge value is not kept for
    the life of the thread;
  - ill-formed UTF-8: `matcher.reset(String(...))`, then `reset("")` so that the string is not
    kept.

### `planned/Program.scala`

`testAt(pv, re, r)`, chosen at build time:

- `PString`: the `RegexOnBytes` in register `r` of the frame (created on the first record of the
  thread), `find` on the bytes;
- `PChoice`: one per branch, chosen by the slot. This is what makes JSON strings take the fast
  path: a JSON value is a choice on its kind, and only bare `PString` (Avro) was recognised before;
- anything else (a string with escapes, a number...): on its JSON view.

## Why it gives the same result

- Strings in Avro and JSON are UTF-8 by definition (Avro spec: "UTF-8 encoded character data";
  RFC 8259: "MUST be encoded using UTF-8"), and the reference decodes them as UTF-8 too.
- The JDK's UTF-8 decoder follows the same table: on well-formed input both give the same chars.
- On ill-formed input, the JDK replaces each ill-formed part by U+FFFD with precise rules; the
  decoder does not reproduce them, it gives up and lets `String` decode, as the reference does.

## Tests

- `planned/Utf8Suite`: the decoder against the JDK's decoder in strict mode (which reports
  ill-formed input instead of replacing it): same chars, or -1 exactly where the JDK reports an
  error. On every sequence of 1 and 2 bytes, the limits of the table, sequences cut by the end,
  200 000 random sequences of 3 and 4 bytes (biased towards the limit bytes), 2 000 random Unicode
  texts; decoded in the middle of an array. Checked to fail without the surrogate rule (`ED`).
- `AvroPlannedSuite`, `JsonPlannedSuite`: regexes on ASCII, accented, `€` and `😀` strings
  (`^...$`, `[a-z]+$`, `\b`, `^.{4}$`, which counts chars, two per emoji, `^$` on the empty
  string), compared with the interpreter.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `select(.name \| test("^jo"))` | 439 µs, 192 B | 330 µs, **6.6 B** | 1 392 µs, 224 B | 1 263 µs, **6.6 B** |

The 6.6 bytes left are the output buffer (about half the records are emitted).

## Limits

- A regex that is not a literal (`test(.pattern)`) is compiled per record, through `Json`.
- JSON strings with escapes go through `Json`.
- Memory: one buffer per thread and per `test`, as large as the longest string seen (at most
  64 Ki chars, 128 KiB).
