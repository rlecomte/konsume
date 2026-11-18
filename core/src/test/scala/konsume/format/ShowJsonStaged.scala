package konsume.format

import konsume.jq.Parser

/** Prints the code generated for a jq program on JSON payloads:
  * `sbt 'core/Test/runMain konsume.format.showJsonStaged "select(.age > 30)"'`
  */
@main def showJsonStaged(program: String): Unit =
  println(JsonStaged.show(Parser.parse(program).fold(throw _, identity)))
