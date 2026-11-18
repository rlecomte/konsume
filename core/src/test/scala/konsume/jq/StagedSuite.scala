package konsume.jq

import io.circe.Json
import konsume.{ConsumeConfig, Engine, Pipeline, Record}

class StagedSuite extends munit.FunSuite:

  private def records(values: List[Json]): Vector[Record[String, Json]] =
    values.zipWithIndex.map((v, i) => Record("topic", 0, i.toLong, 0L, s"key-$i", v)).toVector

  private def pipeline(program: String, engine: Engine) =
    Pipeline.fromConfig[String](ConsumeConfig(Seq("topic"), jq = Some(program), engine = engine)).fold(throw _, identity)

  private def sameJson(a: Json, b: Json): Boolean = Runtime.compare(a, b) == 0

  for program <- Corpus.programs do
    test(s"staged == interpreted: $program") {
      val batch    = records(Corpus.inputs)
      val staged   = pipeline(program, Engine.Staged)(batch).toVector
      val expected = pipeline(program, Engine.Interpreted)(batch).toVector
      assertEquals(staged.map(r => (r.offset, r.key)), expected.map(r => (r.offset, r.key)))
      staged.zip(expected).foreach { (s, e) =>
        assert(sameJson(s.value, e.value), s"offset ${s.offset}: ${s.value.noSpaces} != ${e.value.noSpaces}")
      }
    }

  test("a filter does not copy the records it keeps") {
    val batch = records(Corpus.inputs)
    val out   = pipeline("select(.age > 18)", Engine.Staged)(batch).toVector
    assert(out.nonEmpty)
    out.foreach(r => assert(batch.exists(_ eq r)))
  }

  test("generated code is specialised") {
    val code = Staged.show(Parser.parse("""select(.age > 30 and .name | test("^jo"))""").toOption.get)
    assert(code.contains("Pattern.compile(\"^jo\")"), code)
    assert(code.contains("30.0"), code)
    assert(!code.contains("Interpreter"), code)
  }
