package konsume.jq

import io.circe.Json
import io.circe.parser.parse

import scala.sys.process.*
import scala.util.Try

/** Checks the interpreter (hence the planned engine) against the real jq, when installed. */
class JqDifferentialSuite extends munit.FunSuite:

  private val jqAvailable = Try(Seq("jq", "--version").!!).isSuccess

  private def realJq(program: String, input: Json): Option[Vector[Json]] =
    val out    = new StringBuilder
    val in     = new java.io.ByteArrayInputStream(input.noSpaces.getBytes("UTF-8"))
    val logger = ProcessLogger(line => out.append(line).append('\n'), _ => ())
    val code   = (Seq("jq", "-c", program) #< in).!(logger)
    if code != 0 then None
    else Some(out.toString.linesIterator.filter(_.nonEmpty).map(l => parse(l).fold(throw _, identity)).toVector)

  for program <- Corpus.programs do
    test(s"interpreter == jq: $program") {
      assume(jqAvailable, "jq is not installed")
      val ast = Parser.parse(program).fold(throw _, identity)
      Corpus.inputs.foreach { input =>
        val expected = realJq(program, input)
        val actual   = Corpus.interpret(ast, input)
        val same = (expected, actual) match
          case (Some(e), Some(a)) => e.size == a.size && e.zip(a).forall((x, y) => Runtime.compare(x, y) == 0)
          case (None, None)       => true
          case _                  => false
        assert(same, s"input ${input.noSpaces}: jq=${expected.map(_.map(_.noSpaces))} konsume=${actual.map(_.map(_.noSpaces))}")
      }
    }
