# Build your own konsume

konsume runs jq programs on Kafka records. To understand how, this folder builds a small
konsume, chapter by chapter: the same core ideas, in a few hundred lines, on a language and a
format small enough that nothing hides them:

- **mini-jq**: paths (`.user.age`), `select(...)`, `==`, `>`, `|`; integers and strings;
- **mini-Avro**: a binary format described by a schema, fields written one after the other.

It is a separate sbt project, with no dependency: its code compiles and its tests run with the
rest (`sbt buildYourOwn/test`), so what the pages show is what runs.

## Chapters

1. [The language](01-language.md): the program as a tree, and a parser for it.
2. [The interpreter](02-interpreter.md): decode the whole record, evaluate the tree. The reference.
3. [The demand](03-demand.md): which paths does the program use?
4. [The reading plan](04-plan.md): read those paths into slots, skip the rest.
5. [The program as closures](05-program.md): compare slots, without building values.
6. [Outputs](06-outputs.md): the record unchanged, or a value written from the bytes.
7. [From the small konsume to konsume](07-konsume.md): where each idea lives in the real code, and what it adds.

Each chapter has its code in `src/main/scala/buildyourown` and its tests in `src/test/scala/buildyourown`.
