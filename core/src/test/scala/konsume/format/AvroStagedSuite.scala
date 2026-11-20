package konsume.format

import io.circe.parser.parse
import konsume.jq.Runtime
import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*
import org.apache.avro.generic.{GenericData, GenericRecord}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** The staged Avro decoder against the reference (Apache Avro => JSON => interpreter), on random
  * schemas, records and programs.
  */
class AvroStagedSuite extends munit.FunSuite:

  override def munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val Schemas  = 40
  private val Programs = 20
  private val Records  = 40

  private def check(schema: Schema, batch: Vector[Record[String, Array[Byte]]], program: String): Unit =
    def run(engine: Engine) = Pipeline
      .forBytes[String](ConsumeConfig(Seq("t"), PayloadFormat.Avro, Some(program), engine), Map(1 -> schema).get)
      .fold(e => fail(s"$program: $e"), identity)(batch)
      .toVector
    val expected = run(Engine.Interpreted)
    for engine <- List(Engine.Staged, Engine.Planned) do
      val actual = run(engine)
      def describe(rs: Vector[Record[String, Array[Byte]]]) =
        rs.map(r => if batch.exists(_.value eq r.value) then s"#${r.offset} (original)" else s"#${r.offset} ${String(r.value, UTF_8)}")
      val clue =
        s"""engine: $engine
           |program: $program
           |schema: $schema
           |expected: ${describe(expected).mkString(", ")}
           |actual:   ${describe(actual).mkString(", ")}""".stripMargin
      assertEquals(actual.size, expected.size, clue)
      actual.zip(expected).foreach { (a, e) =>
        assertEquals(a.offset, e.offset, clue)
        val aOriginal = batch.exists(_.value eq a.value)
        val eOriginal = batch.exists(_.value eq e.value)
        assertEquals(aOriginal, eOriginal, clue)
        if !aOriginal then
          val (aj, ej) = (parse(String(a.value, UTF_8)).toOption.get, parse(String(e.value, UTF_8)).toOption.get)
          assert(Runtime.compare(aj, ej) == 0, clue)
      }

  private def randomCase(seed: Int) =
    val gen    = Gen(Random(seed))
    val schema = gen.schema()
    val batch = Vector.tabulate(Records) { i =>
      Record("t", 0, i.toLong, 0L, s"k$i", AvroFixtures.encode(1, gen.datum(schema).asInstanceOf[GenericRecord]))
    }
    (gen, schema, batch)

  for seed <- 1 to Schemas do
    test(s"random schema #$seed: random programs") {
      val (gen, schema, batch) = randomCase(seed)
      for _ <- 1 to Programs do check(schema, batch, gen.program(schema))
    }

  for seed <- 1 to Schemas do
    test(s"random schema #$seed: every leaf compared with a literal of its type") {
      val (gen, schema, batch) = randomCase(seed)
      for (path, leaf) <- gen.leaves(schema) do check(schema, batch, gen.comparison(path, leaf))
    }

  test("recursive schema: skipped generically, read through the JSON view") {
    val schema = AvroFixtures.schema("""
      {"type": "record", "name": "Node", "fields": [
        {"name": "next", "type": ["null", "Node"]},
        {"name": "value", "type": "int"}
      ]}""")
    def node(value: Int, next: GenericRecord | Null): GenericRecord =
      val r = GenericData.Record(schema)
      r.put("value", value)
      r.put("next", next)
      r
    val batch = Vector(node(1, node(2, null)), node(5, null), node(3, node(4, node(5, null)))).zipWithIndex.map((r, i) =>
      Record("t", 0, i.toLong, 0L, "k", AvroFixtures.encode(1, r))
    )
    for program <- List("select(.value > 2)", ".next.value", "select(.next.next.value == 5) | .value", ".next") do
      def run(engine: Engine) = Pipeline
        .forBytes[String](ConsumeConfig(Seq("t"), PayloadFormat.Avro, Some(program), engine), Map(1 -> schema).get)
        .toOption.get(batch).map(r => (r.offset, String(r.value, UTF_8))).toVector
      assertEquals(run(Engine.Staged), run(Engine.Interpreted), program)
      assertEquals(run(Engine.Planned), run(Engine.Interpreted), program)
  }

  test("truncated and corrupted payloads are skipped") {
    val schema = AvroFixtures.schema("""
      {"type": "record", "name": "R", "fields": [{"name": "s", "type": "string"}, {"name": "i", "type": "int"}]}""")
    val good = AvroFixtures.encode(1, { val r = GenericData.Record(schema); r.put("s", "abc"); r.put("i", 42); r })
    val batch = Vector(
      good,
      good.take(7),                                     // truncated in the string
      Confluent.frame(1, Array[Byte](0x7f, 1, 2)),     // string length 63 > remaining
      Confluent.frame(1, Array[Byte](0xff.toByte))     // unterminated varint
    ).zipWithIndex.map((v, i) => Record("t", 0, i.toLong, 0L, "k", v))
    for engine <- List(Engine.Staged, Engine.Planned) do
      val out = Pipeline
        .forBytes[String](ConsumeConfig(Seq("t"), PayloadFormat.Avro, Some("select(.i == 42)"), engine), Map(1 -> schema).get)
        .toOption.get(batch)
      assertEquals(out.map(_.offset).toVector, Vector(0L), engine)
  }

  /** Random schemas, data and programs. Field names come from a small pool, so that programs
    * often hit fields that exist, sometimes ones that do not.
    */
  private final class Gen(rnd: Random):
    private val names   = Vector("a", "b", "c", "d", "e")
    private var counter = 0
    private def fresh(prefix: String) = { counter += 1; s"$prefix$counter" }
    private def pick[T](xs: T*): T = xs(rnd.nextInt(xs.size))

    def schema(): Schema = Schema.Parser().parse(record(depth = 0))

    private def record(depth: Int): String =
      val fields = rnd.shuffle(names).take(2 + rnd.nextInt(3))
      fields.map(f => s"""{"name": "$f", "type": ${tpe(depth + 1)}}""").mkString(s"""{"type": "record", "name": "${fresh("R")}", "fields": [""", ",", "]}")

    private def tpe(depth: Int): String =
      val primitives = Vector(
        "\"null\"", "\"boolean\"", "\"int\"", "\"long\"", "\"float\"", "\"double\"", "\"string\"", "\"bytes\"",
        s"""{"type": "enum", "name": "${fresh("E")}", "symbols": ["FR", "US", "jo"]}""",
        s"""{"type": "fixed", "name": "${fresh("F")}", "size": 2}""",
        """{"type": "bytes", "logicalType": "decimal", "precision": 6, "scale": 2}""",
        """{"type": "long", "logicalType": "timestamp-millis"}"""
      )
      if depth >= 3 then primitives(rnd.nextInt(primitives.size))
      else
        rnd.nextInt(10) match
          case 0 | 1 => record(depth)
          case 2     => s"""{"type": "array", "items": ${tpe(depth + 1)}}"""
          case 3     => s"""{"type": "map", "values": ${tpe(depth + 1)}}"""
          case 4     => s"""["null", ${nonUnion(depth + 1)}]"""
          case 5     => pick("""["int", "string"]""", s"""["null", "string", ${record(depth)}]""")
          case _     => primitives(rnd.nextInt(primitives.size))

    private def nonUnion(depth: Int): String =
      val t = tpe(depth)
      if t.startsWith("[") || t == "\"null\"" then "\"string\"" else t

    def datum(s: Schema): Any = s.getType match
      case NULL    => null
      case BOOLEAN => rnd.nextBoolean()
      case INT     => pick(-2, 0, 1, 30, 31, 100, Int.MaxValue, rnd.nextInt())
      case LONG    => pick(-2L, 0L, 30L, 31L, 1L << 40, Long.MinValue, rnd.nextLong())
      case FLOAT   => pick(1.5f, 30f, -0.0f, Float.NaN, Float.PositiveInfinity, 0.1f, rnd.nextFloat())
      case DOUBLE  => pick(1.5, 30.0, -0.0, Double.NaN, Double.NegativeInfinity, 0.1, rnd.nextDouble() * 100)
      case STRING  => pick("", "FR", "a", "jo", "joe", "é", "😀", "m", "zz", "US")
      case BYTES =>
        if s.getLogicalType != null then ByteBuffer.wrap(java.math.BigInteger.valueOf(rnd.nextInt(100000) - 50000).toByteArray)
        else ByteBuffer.wrap(Array.fill(rnd.nextInt(4))(rnd.nextInt(256).toByte))
      case FIXED => GenericData.Fixed(s, Array.fill(2)(rnd.nextInt(256).toByte))
      case ENUM  => GenericData.EnumSymbol(s, pick(s.getEnumSymbols.asScala.toSeq*))
      case ARRAY => java.util.ArrayList[Any](Seq.fill(rnd.nextInt(4))(datum(s.getElementType)).asJava) // may hold nulls
      case MAP =>
        val m = java.util.HashMap[String, Any]()
        for _ <- 0 until rnd.nextInt(3) do m.put(pick("a", "b", "k"), datum(s.getValueType))
        m
      case UNION => datum(s.getTypes.get(rnd.nextInt(s.getTypes.size)))
      case RECORD =>
        val r = GenericData.Record(s)
        s.getFields.forEach(f => r.put(f.pos, datum(f.schema)))
        r

    /** A path that mostly follows the schema, and the schema at its end (null if none). */
    private def typedPath(s: Schema): (String, Schema) =
      def loop(s: Schema, acc: String): (String, Schema) =
        val records = s.getType match
          case RECORD => List(s)
          case UNION  => s.getTypes.asScala.filter(_.getType == RECORD).toList
          case _      => Nil
        if acc.nonEmpty && (records.isEmpty || rnd.nextInt(3) == 0) then
          if rnd.nextInt(8) == 0 then (acc + ".zz", Schema.create(NULL)) else (acc, s)
        else if records.isEmpty then (acc + "." + pick(names*), Schema.create(NULL))
        else
          val r    = pick(records*)
          val f    = if rnd.nextInt(10) == 0 then "zz" else pick(r.getFields.asScala.toSeq*).name
          val next = Option(r.getField(f)).map(_.schema).getOrElse(Schema.create(NULL))
          loop(next, s"$acc.$f")
      loop(s, "")

    private def path(s: Schema): String = typedPath(s)._1

    /** Every path to a value that is not a record, through records and unions. */
    def leaves(s: Schema, prefix: String = ""): List[(String, Schema)] = s.getType match
      case RECORD => s.getFields.asScala.toList.flatMap(f => leaves(f.schema, s"$prefix.${f.name}"))
      case UNION if s.getTypes.asScala.exists(_.getType == RECORD) =>
        s.getTypes.asScala.toList.filter(_.getType == RECORD).flatMap(leaves(_, prefix)).distinct
      case _ => List(prefix -> s)

    def comparison(path: String, leaf: Schema): String =
      if rnd.nextBoolean() then s"select($path ${op()} ${literalFor(leaf)})"
      else s"select(${literalFor(leaf)} ${op()} $path)"

    /** A literal of the type of `s`, values that the data generator produces. */
    private def literalFor(s: Schema): String =
      val leaf = if s.getType == UNION then pick(s.getTypes.asScala.toSeq*) else s
      leaf.getType match
        case STRING | ENUM                  => pick("\"FR\"", "\"jo\"", "\"m\"", "\"\"", "\"US\"", "\"é\"")
        case INT | LONG | FLOAT | DOUBLE    => pick("30", "0", "1.5", "-2", "31")
        case BOOLEAN                        => pick("true", "false")
        case _                              => literal()

    private def literal(): String = pick("30", "0", "-1", "1.5", "\"FR\"", "\"jo\"", "\"m\"", "null", "true", "false")
    private def op(): String      = pick("==", "!=", "<", "<=", ">", ">=")

    def program(s: Schema): String =
      val p = path(s)
      val q = path(s)
      val (tp, ts) = typedPath(s)
      rnd.nextInt(24) match
        case 18 | 19 | 20 => s"select($tp ${op()} ${literalFor(ts)})"
        case 21           => s"select(${literalFor(ts)} ${op()} $tp)"
        case 22           => s"select($tp ${pick("==", "!=")} ${literalFor(ts)} and $q != null)"
        case 0  => p
        case 1  => s"select($p ${op()} ${literal()})"
        case 2  => s"select($p ${op()} ${literal()} and $q ${op()} ${literal()})"
        case 3  => s"select($p ${op()} ${literal()} or $q)"
        case 4  => s"$p | length"
        case 5  => s"""select($p | test("o"))"""
        case 6  => s"""select($p | startswith("j"))"""
        case 7  => s"select($p)"
        case 8  => s"select($p | not)"
        case 9  => s"$p == $q"
        case 10 => s"select($p != null) | $q"
        case 11 => s"$p[]"
        case 12 => s"select($p[] ${op()} 1)"
        case 13 => "."
        case 14 => s"$p[0]"
        case 15 => s"select(${literal()} ${op()} $p)"
        case 16 => s"$p | select(. ${op()} ${literal()})"
        case _  => s"($p) | length"
