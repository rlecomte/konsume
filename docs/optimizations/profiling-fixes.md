# Two allocations per record, found by profiling

**Programs:** every Avro program (the first one), every Avro program that skips a record field
(the second one).

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

## Lesson

When an allocation does not match what the code should do, profile before guessing: the cause was
in code written long before, and its cost depended on the JIT.
