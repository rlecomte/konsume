# 4. The reading plan

The demand says which paths a program uses. The schema says where each field is in the bytes,
and how long it is. Put together, they give a **reading plan**: the steps that read the used
values and nothing else, built once per program and schema, then run on every record.

## Slots, not values

The interpreter builds a `Value` for each field it reads. The plan builds nothing: it puts what it
reads into **slots**, the cells of a `Long` array, in a `Frame` reused from record to record
(`Frame.scala`).

What a path's slots hold, the plan records as a `PVal` (`PVal.scala`):

```scala
enum PVal:
  case PInt(slot: Int)           // the int is in this slot
  case PStr(off: Int, len: Int)  // the string stays in the bytes: its offset, its length
  case PNull                     // a field the schema does not have
```

A string is not copied out: comparing it with `"FR"` is done on the bytes in place (next
chapter). Only ints and strings go into slots: a record used whole (`.user` emitted) has no
`PVal`, and a program using one is left to the interpreter.

## The instructions

`Plan.scala`:

```scala
enum Instr:
  case ReadInt(slot: Int)           // the int at the cursor, into a slot
  case ReadStr(off: Int, len: Int)  // the offset and length of the string at the cursor
  case Skip(schema: Schema)         // past a value, without decoding it
```

`Skip` is where the gain is: skipping does not decode. An int is 4 bytes, a string its length
byte plus that many bytes, a record its fields (`Reader.skip`).

## Building the plan

`Plan(schema, demand)` walks the schema's fields in order, the way a reader would, and for each
one decides:

- demanded (an int, a string): read it into new slots, record its `PVal`;
- something below it is demanded (`.user` for `.user.age`): go down into it;
- nothing to do with it: `Skip`.

And it **stops after the last field needed**: the fields after it are not even skipped. Unless
something after the current record is needed (`needEnd`): then the walk goes on to the end of the
record, so that the cursor is past it.

Paths the schema does not have (`.nope`, or `.name.x` below a string) get `PNull`: the plan
knows, from the schema alone, that they are always null.

## On John's bytes

`select(.user.age > 30)`, demand `{.user.age}`:

```
plan:   Skip(string)        ReadInt(0)      (stop)
bytes:  04 6A 6F 68 6E      00 00 00 2A     02 46 52   1B 61 20 6C 6F ...
        "john"              42              "FR"       "a long comment nobody reads"
        length 4: skip 5    slot 0 = 42     not read   not read
```

Two steps, 9 bytes touched out of 40, one slot written, no object built. The test cuts John's
bytes right after the age, and the plan reads them all the same.

`select(.user.country == "FR") | .name`, demand `{.user.country, .name}`:

```
ReadStr(0, 1)   Skip(int)   ReadStr(2, 3)   (stop)
.name           .user.age   .user.country
```

`select(.user.age > 1) | .comment` needs a field after `user`: the country is skipped so that the
cursor reaches the comment (`needEnd`).

## Running the plan

`Plan.read(frame)` runs the instructions in order with a `Reader` on the frame's bytes. konsume
turns its instructions into closures once (`AvroPlanned.steps`), and has more of them: unions,
arrays, maps, floats, enums, and records used whole, written later from their offset.

## Tests

`PlanSuite`: the plans of the programs above, written by hand; the slots after reading John, his
bytes cut after the age; a string read as an offset and a length; a record gone through with a
field after it; fields the schema does not have; a record used whole, which gets no value.

Next: [the program as closures](05-program.md).
