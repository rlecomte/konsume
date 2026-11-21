# Arrays walked element by element

**Programs:** an iteration on a path: `.items[] | select(. > 50)`, `.items[]`, `.rs[] | .b`,
`.rs[] | .ys[]`, `.xss[][]`.

## Before

`.items[]` used the whole value of `.items`: the reader read the array into its JSON view (a
`Vector` and a `JNumber` per element), `Runtime.iterate` walked the `Vector`, then the rest of the
program ran on each `Json`: `. > 50` compared `Json`s, `emit` wrote each one with `noSpaces`. 509
bytes per record in Avro, 911 in JSON.

## Idea

What [array indexes](array-index.md) do for one element, for each element in turn. A new step,
`[]`, is "the element at hand": `.items[]` is a path, with its slots, like `.items[0]`. Its slots
change while the program runs: the array is walked by the program, not by the reader.

On `{"items":[10,80,60]}`:

1. the reader reads the record; of `.items`, it only records where the array starts;
2. the program reaches `.items[]`: from that position, it reads 10 into the slot of `.items[]`,
   then runs the rest of the program, `select(. > 50)`, whose input is the path `.items[]`:
   `. > 50` compares the slot, as `.age > 30` does;
3. it reads 80 into the same slot, runs `select` again, which emits it, written from the slot;
4. same for 60, then the array ends.

No `Json` is built: what is left is the floor, a `Record` and its bytes per element emitted.

## How

### Paths (`planned/Demand.scala`, `planned/Syntax.scala`)

- `Step.Each`, shown `[]`.
- `pathOf(p, in, each)`: `.a[]` has the path `.a[]` when `each(.a)`, no path otherwise. Who asks
  decides: the demand analysis answers "iterated at one place only", the program "the reader
  gave a walk for it". The rest of the program (`select(. > 50)`) is then built on the input
  `.items[]`: everything already done for paths (comparisons on slots, output from slots...)
  applies to the element.
- `Syntax.iteratedOnce`: an array iterated at two places (`.a[] > .a[]`, `(.a[] > 1) and (.a[] >
  2)`) is left on the JSON view. The two walks would be nested, in the same slots: the inner one
  would overwrite the element the outer one is using.
- `Demand`: a third kind of use, `Reach`, next to `Value` and `Length`. A walk reaches `.a[]`
  (the entry exists in the tree of the demand, even if nothing is used in the elements: `.a[] |
  1`) and no longer uses the value of `.a`.
- `Demand.withoutWalk`: for a reader that cannot walk an array, every `[]` below becomes "read
  this value whole", the behaviour from before. It applies to the whole subtree: the Avro reader
  uses the demand of a missing field, or of a record read whole, without going through `value`.

### The interface (`planned/Program.scala`)

```scala
trait Walk:
  def apply(f: Frame, body: Sink): Boolean // false: not an array of the payload
  def hold(f: Frame, element: Json): Unit  // the element at hand is this `Json`
```

Given by the reader, in a `Map[Path, Walk]`, to `Planned.build`, then to the program.

### Program

- `Iterate(t)`, when the path of `t` has a walk: `walk(f, k)` instead of `Runtime.iterate`. If
  the walk answers "not an array" (JSON: an object, null...), the program iterates the JSON view of
  the value, giving each element to `hold`: same values, same errors as the reference.
- When `t` is a static path, it is not run first: for a JSON value, that would build the JSON view
  of the array, unused (found by profiling: the first measure in JSON showed no gain). It is run
  when it has code (`select(c) | .items[]`).
- `emitting(in, k)`: when the output of the program is the value of a path read into slots, it
  is written from the slots (what `atPath` did for `.name`). Used by `atPath`, `Identity` (`.a[]`
  alone) and `select`, which outputs its input.

### Avro reader

- `AvroPlan.value`: an `ARRAY` whose demand is only `[]` is walked. A record goes down into its
  fields with the demand as is, so `.user.items[]` is walked too. Anything else (unions, maps, an
  array used otherwise) goes through `withoutWalk`: read whole, as before.
- `AvroPlan.walk`: while reading the record, `ReadPos(slot)` records the position of the array,
  followed by a `Skip` only when something after the array is needed. The plan of one element is
  `value` on the element schema, at the path `.items[]`: `.rs[] | .b` works.
- `AvroPlanned.ArrayWalk`: from the position, block by block (the size of a sized block is read
  and ignored: every element is read). For each element: read it into its slots, remember the
  position after it, run the program, put the cursor back. A walk inside the element (`.rs[] |
  .ys[]`) moved it.
- `hold` is never called: the schema says it is an array.

### JSON reader

- A node walked has its value slots (kind, start, length: to find the array, and to iterate its
  JSON view when it is not an array) and an `Each(node, held)`: the node of the element, read
  like any other, and a slot in `refs` for an element given as a `Json`.
- Reading the record is unchanged: the array is validated, skipped, its position recorded.
- `ArrayWalk.apply`: `false` if the kind found is not `Array`. Otherwise, from the start of the
  array, for each element: `forgetBelow` (nothing left from the previous element), `readValue`,
  run the program, put the cursor back.
- `hold`: the kind of the element becomes `Held` (8, after the 8 kinds of the cursor), the `Json`
  goes into `refs(held)`.
- Values: every path below the element is a choice on the kind of the element. Kinds 0 to 7: the
  value read into the slots, as before. `Held`: the same path, on the `Json` held (`PAny`, a new
  `PVal`: any JSON value, in `refs`). `.items[].a.b` is then `PField(PField(PAny(held), .a), .b)`.
- Below a walk inside the element (`.xs[].ys[]`), the element is the inner walk's: it gives it,
  from the payload or held. The choices of the outer levels let it through (`under` has a branch
  for `Held`).

### An undecodable record emits nothing

Before, a decoding error happened while reading the record, before any output. A walked array is
read while the program runs: when its third element is corrupted, the first two may already be
emitted. `Planned.build` takes the size of the output before the record and, on a `DecodeError` or
an `IndexOutOfBoundsException` (a truncated payload), removes what the record emitted before
throwing again. A `JqError` still keeps them: in jq, outputs emitted before an error are kept.

## Why it gives the same result

- The element read into slots is the value the JSON view would give, by the same reader as for
  any path; everything after is the code that already handles paths.
- Whatever is not an array of the payload goes through `Runtime.iterate` on the JSON view, as
  before: objects (their values, duplicate keys as circe keeps them), null, numbers (jq's error).
- Elements and walks are synchronous: the program is done with an element before the next one is
  read. The only way to see an element overwritten is a second walk of the same array inside the
  first one, which `iteratedOnce` excludes.

## Tests

- `AvroPlannedSuite`, "arrays walked by the program": Avro written by hand. `xs` empty, in one
  block, a block and a sized block, two sized blocks; `rs` of records each holding an array `ys`
  followed by a field (a walk inside a walk, then a field read after it), in a sized block;
  arrays of arrays; three corrupted records (in `xs`, in an element of `rs`, a string longer than
  the payload). 21 programs, among which `.rs[] | .ys[]`, `.xss[][]`, `.rs[] | select(.ys[] > 6) |
  .b`, `.xs[] > .xs[]`, `select(.xs[] > 2) | .xs[]`.
- `JsonPlannedSuite`, "arrays walked": null, a number, a string, an object instead of an array;
  duplicate keys both ways; nested walks whose elements change shape (`[{"ys":[1]},5,...]`); three
  levels, one held. 21 programs, among which `.xs[] | .ys[] | 1`: a body that does not read the
  element, the only one that shows a walk of bytes left by the previous element.
- Checked to fail: without putting the cursor back (Avro, JSON), without `forgetBelow` before an
  element, without cleaning the output on a decoding error, with an array iterated twice accepted.
- A `forgetBelow` in `hold` was found to change nothing, and removed: below a held element a kind
  can only be left by a walk of the same array, inside an outer walk, which forgets everything
  below its element first.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `.items[] \| select(. > 50)` | 968 µs, 509 B | 568 µs, **83 B** | 2 299 µs, 911 B | 1 618 µs, **83 B** |

The 83 bytes are the records emitted and their bytes: a little over one element per record.

## Limits

- Avro: a nullable array (`["null", {"type": "array", ...}]`), a map, an array used both whole
  and walked, are read whole. A union would need a walk per branch.
- An array iterated at two places in the program: read whole.
- `.items[].b` (without a pipe) and `.items[][0]`: the field is taken on the element's JSON view.
- JSON: the array is read twice, once to validate the payload, once by the walk.
- Avro: a corruption after the last field read is not seen, as for every program (README).
