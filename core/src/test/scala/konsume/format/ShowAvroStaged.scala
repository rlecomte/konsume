package konsume.format

import konsume.jq.Parser

/** Prints the code generated for a jq program on an Avro schema:
  * `sbt 'core/Test/runMain konsume.format.showAvroStaged <schema.avsc> "select(.age > 30)"'`
  */
@main def showAvroStaged(schemaFile: String, program: String): Unit =
  val schema = String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(schemaFile)))
  println(AvroStaged.show(Parser.parse(program).fold(throw _, identity), AvroFixtures.schema(schema)))
