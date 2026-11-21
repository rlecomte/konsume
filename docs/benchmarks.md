# Benchmarks

Every performance change is measured, compared with the previous measure, and committed with
its results: `bench/results` holds the history, one file per run.

## Run

```
bench/run.sh          # the planned engine, Avro and JSON (about 12 minutes)
bench/run.sh all      # both engines
```

`ShapesBench`: one program per shape (a filter, a regex, a projection, an index, an iteration...),
on the same 10k events, with JMH and `-prof gc`, always with the same settings (2 forks, 3 warmup
and 5 measurement iterations of 1 s). JMH's results go to
`bench/results/<date-time>-<commit>[-dirty].json`; `-dirty` means the working tree had changes:
measured on top of that commit, which is the case of a change measured before being committed.

Two numbers per program and format: µs per batch of 10k records, and bytes allocated per record.
Allocations are stable to the byte; times vary by a few % from run to run, and with the machine:
compare results of the same machine.

## Compare

```
bench/compare.sh                       # the two most recent results
bench/compare.sh before.json after.json
```

One line per program and format, before and after. Marked:

- `SLOWER`: slower by more than 10 % and by more than both error margins together (`faster`
  for the opposite);
- `MORE`: one byte per record or more allocated (`less` for the opposite).

It exits with 1 when something is `SLOWER` or `MORE`.

## Profile

```
bench/profile.sh <program> <format> [engine]     # e.g. bench/profile.sh projectObj Avro
```

One case of `ShapesBench` recorded with JFR (`bench/target`, not committed), then summarised by
`konsume.bench.Profile`: the methods the CPU is in (self), the konsume methods under which it is
(total), and what allocates (sampled, weighted by bytes). Only the samples in konsume, circe or
jawn code are counted, not JMH's.

## The rule

1. Before a change: the last result in `bench/results` is the one of the commit the change starts
   from (otherwise run `bench/run.sh` first).
2. After it: `bench/run.sh`, then `bench/compare.sh`.
3. Commit the new result with the change. A regression is fixed, or explained in the commit
   message.
