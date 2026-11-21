# Array indexes in paths

**Programs:** an element of an array: `.items[0]`, `select(.items[0] > 50)`, `.rs[1].b`.

## Before

An index was not part of a path. `.items[0]` used the whole value of `.items`: the reader read the
array into its JSON view, then `Runtime.index` took the element. 522 bytes per record in Avro, 854
in JSON.

## Idea

Make `.items[0]` a path like `.user.age`: the reader walks the array, skips the elements before
the wanted one, and reads it into slots. The program then reads the slots, compares them, writes
the output from them, as for a field.

## How

### Paths (`planned/Demand.scala`)

```scala
enum Step:
  case Key(name: String) // .user
  case At(index: Int)    // [0]
type Path = List[Step]
```

`.items[0]` is `List(Key("items"), At(0))`. `Demand.children` is keyed by `Step`. `PField(parent,
step)` (a step below a value read whole) is computed by `Runtime.field` or `Runtime.index`.

### Analysis (`planned/Syntax.scala`, `Demand.uses`)

`staticPath` and `pathOf` accept `Jq.Index(t, i)` when `i >= 0`. `uses`: an index that is part of
a static path consumes nothing itself, like a field. Negative indexes (`.items[-1]`) need the size
of the array before knowing which element to keep: they stay on the generic path.

### Program (`planned/Program.scala`)

What a field on a static path did (write the output from the slots, or read the value from the
slots, or pass `Undecoded`) moved to `atPath(path, k)`, called for `Jq.Field` and `Jq.Index`.
Comparisons already went through `staticPath`: `select(.items[0] > 50)` uses the slot as is.

### Avro reader

An Avro array is a series of blocks: `count, elements..., count, elements..., 0`. A negative count
is followed by the size of the block in bytes. Every number is a zigzag varint: no fixed size, no
index table; reaching element `i` means skipping the elements before it.

- `AvroPlan.value`: an array whose demand has only indexes (not its value, length or a field) gets
  `Elements(wanted, schema, toEnd)`. Each `Wanted(index, found, read)` has a `found` slot and the
  instructions reading the element, planned by `value` on the element schema (so `.rs[1].b`
  works). The element is read to its end (`needEnd = true`): the walk goes on after it.
- The value of each path below an element is `PChoice(found, [null, value])`: an array too short
  gives null, as in jq, and the slots left by the previous record are never read.
- `AvroPlanned.ElementsStep` resets the `found` slots, then walks the blocks: a wanted element is
  read, the others skipped. A sized block with no wanted element is skipped at once
  (`cur.skip(size)`). After the last wanted element, it stops, unless `toEnd`: something after the
  array in the record is needed, so the walk must end exactly after it (`needEnd` of the planner).

### JSON reader

- A node has `elements: Array[Element(index, node)]`, real nodes with their own slots, like the
  fields of an object.
- `readArray`: walks the array with `JsonCursor.beginArray` / `nextElement`, reads the wanted
  elements into their nodes, skips (and validates) the others, returns the number of elements. It
  always goes to the closing `]`: the payload is validated whole.
- No `found` slot: an element not found keeps the kind `Null`. `forgetBelow` resets the kinds of
  everything below a node before reading an object or an array, so that nothing is left from a
  previous array (`{"xs":[1,2,3],"xs":[9]}`: `.xs[1]` is null).
- `values`: the value of an element is null under null, the value under an array, an error
  (`Cannot index ... with number`) under anything else.

## Tests

- `AvroPlannedSuite`, "array elements: any shape of blocks...": Avro written by hand (Apache's
  writer always writes one block without its size): one block, several blocks, sized blocks, both,
  an array too short, empty, an array of records, a field after the arrays, an index on an int.
  Checked to fail when a sized block is skipped one byte too far.
- `JsonPlannedSuite`, "array elements: short arrays, duplicate keys...": duplicate keys in both
  orders, an array replaced by a number, an object or null instead of an array, nested arrays,
  objects in arrays, spaces. Checked to fail without the reset before an array.

## Measured

| Program | Avro before | Avro after | JSON before | JSON after |
|---|---|---|---|---|
| `.items[0]` | 943 µs, 522 B | 448 µs, **85 B** | 2 152 µs, 854 B | 1 190 µs, **85 B** |

## Limits

- Negative indexes: generic path.
- An array used both whole and by index (`.items` and `.items[0]`) is read whole; the element is
  taken on its JSON view.
- Avro: stopping after the element means a payload corrupted after it is accepted (the reference
  rejects it), as for fields after the last one needed.
