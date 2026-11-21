# Two paths compared in place

**Programs:** a comparison between two paths of the record: `select(.name == .user.country)`,
`.xs[0] < .xs[2]`.

## Before

Comparisons with a literal (`.age > 30`, `.country == "FR"`) were already specialised on the slot.
A comparison between two paths was not: both values were turned into `Json` (for two strings: two
`String` and two `JString` per record) and compared by `Runtime.compare`. 129 bytes per record, for
a program that emits nothing.

## Idea

Compare the two slots directly when their types allow it: two strings as UTF-8 bytes, two numbers
as doubles, two values of different types by their rank in jq's order. Anything else on their JSON
views.

## How

`Program.boolValue`, case `Compare`: when both operands are paths of the record
(`Operand.At(a)`, `Operand.At(b)`), `compareSlots(a, op, b)`.

`compareSlots` decides at build time, from the two `PVal`:

| `a`, `b` | Comparison |
|---|---|
| a constant on either side | the existing comparison with a literal (`compareSlot`, `op.flip` when the constant is on the left) |
| `PChoice` on either side | one comparison per branch, chosen by the slot at run time |
| two `PString` | `==` / `!=`: `Arrays.equals` on the two byte ranges; `<`, `<=`...: `Arrays.compareUnsigned` |
| two numbers (`PLong`, JSON `PNumber`) | as doubles (`number(v)`) |
| both types known (`staticRank`) and different | constant: jq orders types (null < false < true < numbers < strings < arrays < objects) |
| anything else | `materialize` both, `Runtime.compare` |

For JSON, both values are `PChoice` on their kind: the dispatch builds one closure per pair of
branches that can meet. Only the object branch of a parent goes deeper, so their number grows with
the depth of the paths, not exponentially.

## Why it gives the same result

- Strings: the unsigned order of UTF-8 bytes is the order of code points, which is jq's order
  (`Runtime.compareStrings` compares code points). Equality of strings is equality of bytes.
- Numbers: jq compares numbers as doubles (`Runtime.compareNumbers`).
- Different types: `Runtime.compare` compares ranks first.

## A detail: no boxing

`number(v)` returns a `DoubleAt` (`def apply(f: Frame): Double`), not a `Frame => Double`:
`Function1` is not specialised when its argument is an object, so its `apply` would box every
double it returns. Same for `LongAt` in [`length`](length-without-value.md).

## Tests

The random programs of both suites include `$p == $q` on random paths and documents (strings,
numbers, null, missing keys, objects, arrays), compared with the interpreter.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `select(.name == .user.country)` | 483 µs, 129 B | 318 µs, **0 B** | 1 495 µs, 129 B | 1 303 µs, **0 B** |

## Limits

Avro floats and doubles (`PDouble`: NaN and infinities are null in the JSON view), booleans,
enums, records read whole, JSON strings with escapes: through `Json`.
