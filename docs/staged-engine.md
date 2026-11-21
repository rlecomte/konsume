# The staged engine: what was measured and learnt

konsume first compiled each jq program to JVM bytecode at runtime, with Scala 3 multi-stage
programming (`scala.quoted.staging`). It was replaced by the planned engine
(`planned/`, with `avro/AvroPlanned` and `json/JsonPlanned`), mainly for memory. This page keeps what we learnt, so that the question
can be reopened with numbers rather than from scratch.

## Where to find it

Last commit with the staged engine, next to the planned one (paths below are as of that commit):
[`9ab5db4`](https://github.com/rlecomte/konsume/tree/9ab5db4baf72f2e521d8fcef202827ff91e0102b)
(`git checkout 9ab5db4`).

| File | Role |
|---|---|
| `core/src/main/scala/konsume/jq/Gen.scala` | code generation for jq, shared by every format |
| `core/src/main/scala/konsume/jq/StagingCompiler.scala` | the staging compiler, recycled every 500 compilations |
| `core/src/main/scala/konsume/format/AvroStaged.scala` | Avro: reading plan compiled to code |
| `core/src/main/scala/konsume/format/JsonStaged.scala` | JSON: staged scanner of the bytes (its cursor, `JsonCursor`, is kept) |
| `core/src/main/scala/konsume/format/Plan.scala` | `demand`: paths used, found by a dry run of `Gen` |
| `bench/.../CompilationCurveBench.scala`, `MetaspaceProbe.scala`, `bench/compilation-curve.json` | compilation time and Metaspace measurements |

## How it worked

- `Gen` generates the code of the program in continuation-passing style: continuations are
  inlined at staging time, so the program is fused into the loop over the batch. Each value
  carries what is known about it statically (`SVal`: a constant, a `long` in a local variable,
  UTF-8 bytes in place, a union index...), so comparisons with a literal are specialised and
  constants folded.
- `demand`: the program is first generated in "recording mode" to find which paths from the root
  it uses; a second generation reads them from local variables filled by the decoder.
- For Avro, the reading plan (fields in schema order, needed ones into typed variables, the others
  skipped, stop after the last needed one) was compiled with the program, per writer schema.
- For JSON, a staged scanner compared keys as bytes and kept only the values of used paths.

The reading plan (`AvroPlan`) is the part the planned engine kept, unchanged.

## Measurements (JDK 25)

10k Avro records (~19 bytes each), µs per batch, and bytes allocated per record:

| Program | Staged | Planned | Interpreted |
|---|---:|---:|---:|
| `select(.user.age > 30 and .user.country == "FR")` | 119 (1.6) | 348 (1.6) | 3 047 (2 139) |
| `select(.name \| test("^jo"))` | 350 (192) | 464 (220) | 3 507 (2 702) |
| `.user.country` | 309 (189) | 561 (265) | 2 888 (2 268) |
| `.items[] \| select(. > 50)` | 665 (540) | 940 (493) | 3 501 (2 488) |

JSON, same filter, 10k records (~65 bytes): staged scanner 1 053 µs (416 KB per batch), planned
1 404 µs (16 KB, the output buffer), circe then interpreted 3 097 µs (20 MB).

Time to build one program, median in a fresh JVM:

| N-th program | Staged | Planned |
|---|---:|---:|
| 1st | 1 828 ms | 154 ms (Avro and circe classes loading) |
| 2nd | 76 ms | 5 ms |
| 10th | 45 ms | 0.3 ms |
| 200th | 11 ms | 0.1 ms |

Memory kept, fresh JVM, after a full GC (`MemoryProbe`), heap / Metaspace:

| | Staged | Planned |
|---|---|---|
| fixed cost, first program | +36 MiB / +35 MiB (the compiler and its state) | ~0 / +4 MiB (classes of the engine) |
| per program alive | ~9 KiB heap, ~14 KiB Metaspace, 3 classes | ~1 KiB heap, no class |

## What we learnt

1. **Most of the gain comes from the reading plan, not from generating code.** Avro filter:
   interpreted 3 047 µs, planned 348, staged 119. Reading only the fields the program uses, as
   slots, is 9x; generating bytecode on top is another 1.3x to 2.9x.
2. **What bytecode still buys is fusion.** Once the planned engine allocated nothing of its own
   per record (frame reused, continuations fixed at build time), its throughput did not move: the
   remaining gap is the indirect calls between closures, which the JIT cannot inline (one call
   site sees many lambda classes). It weighs most when the work per record is tiny (12 ns staged,
   35 ns planned, for the filter).
3. **The staging compiler is expensive to keep:** ~1.8 s for the first compilation, tens of ms
   after, ~70 MiB (heap + Metaspace) as soon as one program is compiled.
4. **It leaks.** A `scala.quoted.staging.Compiler` keeps one `ContextBase` for all its runs, and
   about 24 KiB of heap per compilation is never released, even once the compiled program is
   dropped: 245 MiB left after 10k programs. What is kept is compiler internals (types, symbols,
   denotations, trees). Root cause not found:
   - not `ContextBase.magicHeaderCache`: it grows by 2 entries per compilation, but emptying it
     changes nothing;
   - not the generated class `Generated$Code$From$Quoted` piling up in the root package scope:
     only one is found there at each run.

   Workaround that works: a new `Compiler` every 500 compilations (54 MiB left after 10k
   programs instead of 245); the first compilation on a new instance takes 120-250 ms, the JIT
   being warm.
5. **No global cache of compiled programs.** Each one holds its classes in Metaspace: a cache
   keyed by program grows forever when users write their own filters. Compiling once per
   pipeline is fast enough after warm-up.
6. `Compiler.run` is already `synchronized` (and `withQuotes` goes through it): no need for a lock
   of our own, but compilations are serialized.
7. Staged code needs `fork := true`: the staging compiler takes its classpath from
   `java.class.path`, not from sbt's class loaders.
8. **Bug found in `Gen`:** `select(c).a` (a field access right after `select`, no `|`) ignored the
   `select`. `pathOf` treated `select` as keeping the path, and a field access on a static path
   did not evaluate its target. The planned engine only takes that shortcut for paths made of
   field accesses alone (`staticPath`).
9. A dry run of the code generator (`Plan.demand`) and a plain analysis of the syntax tree
   (`planned/Demand`) find the same paths; the second needs no compiler and is 30 lines.
10. Known limits of the staged decoders, for the record: JSON did not detect invalid UTF-8;
    generated methods could exceed the JIT's 8000-byte limit on very large schemas.

## If CPU becomes the bottleneck

- Tiered execution, like the JVM's own JIT: start every pipeline on the planned engine, and
  compile the staged version in the background for pipelines that run long or hot, then swap.
  Short interactive sessions never load the compiler; recycle it to bound the leak.
- Or reduce the indirect calls of the planned engine: fuse frequent shapes (`and` of two slot
  comparisons, a whole `select` at the root) into single closures.
