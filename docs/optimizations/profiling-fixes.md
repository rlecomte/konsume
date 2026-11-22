# Found by profiling

**Programs:** every Avro program (the first and the third), every Avro program that skips a
record field (the second one).

## How they were found

After [`length` without the value](length-without-value.md), `select(.items | length > 2)` still
allocated 19 bytes per record in Avro, where none was expected. A JFR recording
(`-XX:StartFlightRecording`, events `jdk.ObjectAllocationSample`, aggregated by class and stack)
showed that neither came from the new code.

They were not visible before because the JIT removes them sometimes (escape analysis, when the
method is inlined): the filter program allocated 1.7 bytes per record with the same code. Whether
it does depends on inlining decisions, so on the program and on what else runs in the JVM.

## 1. `Confluent.schemaId`: a `ByteBuffer` per record

90 % of the samples. The schema id of each Avro record (4 bytes, big endian, after the magic byte)
was read with `ByteBuffer.wrap(bytes, 1, 4).getInt`: a `HeapByteBuffer` per record.

Now read by hand:

```scala
(bytes(1) & 0xff) << 24 | (bytes(2) & 0xff) << 16 | (bytes(3) & 0xff) << 8 | (bytes(4) & 0xff)
```

## 2. `AvroRuntime.skip`: a lambda per skipped record

Skipping a record value (the `user` field, before `items`) used
`schema.getFields.forEach(f => skip(cur, f.schema))`: the lambda captures the cursor, so a new one
is allocated at each call. Now an index loop over the fields.

## Measured

`select(.items | length > 2)`, Avro: 19.3 → **3.3 bytes per record**. `.items[0]` and
`.items[] | ...` skip `user` too.

## 3. Two lookups per record: the frame, the program of the schema

Found later, with a CPU profile (`bench/profile.sh projectObj Avro`): about 40 % of the time of
`.user` in Avro went into two lookups made for each record.

- `ThreadLocal.get()` in `Planned.build`, to find the frame of the thread (19 %). A `ThreadLocal`
  is a slot in a hash table of the thread; here the slot was taken by another one and Java probed
  the next slots (`getEntryAfterMiss`). Whether it does depends on how many `ThreadLocal`s exist
  and in which order they were created: nothing showed in JSON.
- `ConcurrentHashMap.get` in `Pipeline`, to find the program built for the schema id (20 %).

Both are now looked up once per batch: `Planned.Fn.local()` gives the engine of the calling
thread (its frame included), used for the whole batch; the Avro pipeline keeps the program of the
last schema id seen in the batch, and looks it up again only when the id changes. A batch is run
by one thread, so nothing is shared. Tested with a batch alternating two schemas.

Measured (`bench/compare.sh`, results `20261121-2233-e75eaf9` → `20261121-2244-b1e6163-dirty`):
every Avro program 15 to 27 % faster (`.items[] | select(. > 50)`: 717 → 525 µs, `.user`: 517 →
435 µs); JSON within the noise.

## Tried, no gain: resetting only the kinds in JSON

The same kind of profile (`bench/profile.sh filter Json`) showed `Arrays.fill` at 11 % and
`forgetBelow` at 5 %: each JSON record starts by setting all the slots of the frame to 0, then
the kinds below the root object to null again. Resetting only the kind slots, once, removed
`Arrays.fill` from the profile, but a profile after the change put about as much time in the loop
that replaced it, and `bench/compare.sh` (`20261121-2244-b1e6163-dirty` →
`20261122-1558-d4ec69f-dirty`, result committed) showed no gain beyond the noise on JSON
(-13 % to +6 %). Undone: a few more lines and a parameter for nothing measurable. The self time
of a short intrinsic (`Arrays.fill` on twenty longs) in a sampled profile is not what removing it
saves.

## Lesson

When an allocation does not match what the code should do, profile before guessing: the cause was
in code written long before, and its cost depended on the JIT. And measure the change itself: a
profile says where time is spent, not how much a change will save.
