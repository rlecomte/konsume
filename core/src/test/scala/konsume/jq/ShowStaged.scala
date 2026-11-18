package konsume.jq

/** Prints the code generated for a jq program:
  * `sbt 'core/Test/runMain konsume.jq.showStaged "select(.age > 30)"'`
  */
@main def showStaged(program: String): Unit =
  println(Staged.show(Parser.parse(program).fold(throw _, identity)))
