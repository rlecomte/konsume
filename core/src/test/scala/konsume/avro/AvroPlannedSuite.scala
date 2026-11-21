package konsume.avro

import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline, Record}
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*
import org.apache.avro.generic.{GenericData, GenericRecord}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** The planned Avro engine against the reference (Apache Avro => JSON => interpreter), on random
  * schemas, records and programs.
  */
class AvroPlannedSuite extends munit.FunSuite:

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
    for engine <- List(Engine.Planned) do
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
        // re-encoded outputs: the very same bytes as the reference writes
        if !aOriginal then assertEquals(String(a.value, UTF_8), String(e.value, UTF_8), clue)
      }

  /** Avro written by hand: Apache's writer always writes an array in one block, without its size. */
  private object Hand:
    def long(v: Long): Array[Byte] =
      val out = java.io.ByteArrayOutputStream()
      var z   = (v << 1) ^ (v >> 63) // zigzag
      while (z & ~0x7fL) != 0 do
        out.write(((z & 0x7f) | 0x80).toInt)
        z >>>= 7
      out.write(z.toInt)
      out.toByteArray
    def str(x: String)  = { val b = x.getBytes(UTF_8); long(b.length) ++ b }
    def block(xs: Int*) = long(xs.size) ++ xs.flatMap(long(_))
    def sized(xs: Int*) = { val body = xs.flatMap(long(_)).toArray; long(-xs.size) ++ long(body.length) ++ body }

  /** The same outputs as the reference, as JSON values: keys in any order. */
  private def checkMeaning(schema: Schema, batch: Vector[Record[String, Array[Byte]]], program: String): Unit =
    def run(engine: Engine) = Pipeline
      .forBytes[String](ConsumeConfig(Seq("t"), PayloadFormat.Avro, Some(program), engine), Map(1 -> schema).get)
      .fold(e => fail(s"$program: $e"), identity)(batch)
      .map(r => r.offset -> io.circe.parser.parse(String(r.value, UTF_8)).fold(e => fail(s"$program: $e"), identity))
      .toVector
    val (actual, expected) = (run(Engine.Planned), run(Engine.Interpreted))
    assertEquals(actual.map(_._1), expected.map(_._1), program)
    actual.zip(expected).foreach { case ((o, a), (_, e)) =>
      assert(konsume.jq.Runtime.compare(a, e) == 0, s"$program, #$o\n  actual: ${a.noSpaces}\nexpected: ${e.noSpaces}")
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
      assertEquals(run(Engine.Planned), run(Engine.Interpreted), program)
  }

  test("string functions on multi-byte UTF-8, iteration over computed values") {
    val schema = AvroFixtures.schema("""
      {"type": "record", "name": "S", "fields": [
        {"name": "s", "type": "string"},
        {"name": "u", "type": ["null", "string", "int"]},
        {"name": "xs", "type": {"type": "array", "items": "int"}}]}""")
    val values = List[(String, Any)](
      "jo" -> null, "jöhn" -> "élan", "" -> 3, "élan" -> "", "nej" -> "€uro€", "€uro€" -> "jo", "ö" -> null,
      "j😀n" -> "😀😀", "john" -> "a b"
    )
    val batch = values.zipWithIndex.map { case ((str, u), i) =>
      val r = GenericData.Record(schema)
      r.put("s", str)
      r.put("u", u)
      r.put("xs", java.util.List.of[Integer](i, i * 2, 5))
      Record("t", 0, i.toLong, 0L, "k", AvroFixtures.encode(1, r))
    }.toVector
    val programs = List(
      """select(.s | startswith("jö"))""", """select(.s | startswith(""))""", """select(.s | endswith("€"))""",
      """select(.s | endswith("n"))""", """select(.s | test("ö"))""", """select(.u | startswith("é"))""",
      """select(.u | endswith("o"))""", """select(.s | startswith(1))""", """select(.u | test("^j"))""",
      """.xs[] | select(. > 4)""", """.xs[] | select(. == 5 or . < 2)""", """.xs[] | select(2 < .)""",
      """.s | startswith("j")""", """.xs[] | select(. != "a")""",
      """select(.s | test("^j.h?n$"))""", """select(.s | test("[a-z]+$"))""", """select(.s | test("\\bn"))""",
      """select(.s | test("^.{4}$"))""", """select(.s | test("€"))""", """select(.u | test("^.{4}$"))""", """select(.s | test("^$"))"""
    )
    programs.foreach(check(schema, batch, _))
  }

  test("array elements: any shape of blocks, sized blocks, short arrays, a field after") {
    val schema = AvroFixtures.schema("""
      {"type": "record", "name": "R", "fields": [
        {"name": "xs", "type": {"type": "array", "items": "int"}},
        {"name": "rs", "type": {"type": "array", "items":
          {"type": "record", "name": "E", "fields": [{"name": "a", "type": "int"}, {"name": "b", "type": "string"}]}}},
        {"name": "s", "type": "string"}]}""")
    import Hand.*
    def elem(a: Int, b: String) = long(a) ++ str(b)
    val rs                   = long(2) ++ elem(1, "x") ++ elem(2, "y") ++ long(0)
    val arrays = List(
      block(1, 2, 3) ++ long(0),             // one block
      block(1) ++ block(2, 3) ++ long(0),    // several blocks
      sized(1, 2) ++ sized(3, 4) ++ long(0), // sized blocks: those without a wanted element skipped at once
      sized(7) ++ block(8, 9) ++ long(0),    // both kinds
      block(5) ++ long(0),                   // too short
      long(0)                                // empty
    )
    val batch = arrays.zipWithIndex.map((xs, i) => Record("t", 0, i.toLong, 0L, "k", Confluent.frame(1, xs ++ rs ++ str("FR")))).toVector
    val programs = List(
      ".xs[0]", ".xs[1]", ".xs[2]", ".xs[3]", ".xs[0] == .xs[1]", "select(.xs[0] < .xs[2])",
      "select(.xs[2] > 2) | .s", """select(.xs[1] != null and .s == "FR")""", ".rs[1].b", ".rs[0].a",
      "select(.rs[1].a == 2) | .xs[0]", ".rs[5].b", ".xs[0].a"
    )
    programs.foreach(check(schema, batch, _))
  }

  test("arrays walked by the program: blocks, nested walks, a field after, corrupted elements") {
    import Hand.*
    val schema = AvroFixtures.schema("""
      {"type": "record", "name": "R", "fields": [
        {"name": "xs", "type": {"type": "array", "items": "int"}},
        {"name": "rs", "type": {"type": "array", "items":
          {"type": "record", "name": "E", "fields": [
            {"name": "a", "type": "int"},
            {"name": "ys", "type": {"type": "array", "items": "int"}},
            {"name": "b", "type": "string"}]}}},
        {"name": "xss", "type": {"type": "array", "items": {"type": "array", "items": "int"}}},
        {"name": "s", "type": "string"}]}""")
    def e(a: Int, ys: Array[Byte], b: String) = long(a) ++ ys ++ str(b)
    // each element of rs has an array, walked inside the walk of rs, and a field after it
    val rs = List(
      long(0),
      long(1) ++ e(1, block(5, 6) ++ long(0), "x") ++ long(0),
      long(2) ++ e(1, sized(7) ++ block(8) ++ long(0), "x") ++ e(3, long(0), "y") ++ long(0),
      { // a sized block of records, then a plain one
        val body = e(2, block(1) ++ long(0), "p") ++ e(4, sized(9, 2) ++ long(0), "q")
        long(-2) ++ long(body.length) ++ body ++ long(1) ++ e(5, long(0), "r") ++ long(0)
      }
    )
    val xs  = List(long(0), block(1, 2, 3) ++ long(0), block(1) ++ sized(2, 3) ++ long(0), sized(4) ++ sized(5) ++ long(0))
    val xss = List(long(0), long(1) ++ block(1, 2) ++ long(0) ++ long(0), long(2) ++ long(0) ++ sized(3) ++ long(0) ++ long(0))
    val good =
      for (x, i) <- xs.zipWithIndex; (r, j) <- rs.zipWithIndex
      yield x ++ r ++ xss((i + j) % xss.size) ++ str(if (i + j) % 2 == 0 then "FR" else "DE")
    val corrupted = List(
      long(3) ++ long(1) ++ long(2),                                        // truncated in xs
      block(1, 2) ++ long(0) ++ long(1) ++ long(1) ++ long(2) ++ long(5),   // truncated in the ys of an element of rs
      block(1, 2) ++ long(0) ++ long(1) ++ long(1) ++ long(0) ++ long(40) ++ "ab".getBytes(UTF_8) // b beyond the payload
    )
    def batch(payloads: List[Array[Byte]]) =
      payloads.zipWithIndex.map((v, i) => Record("t", 0, i.toLong, 0L, "k", Confluent.frame(1, v))).toVector
    val programs = List(
      ".xs[]", ".xs[] | select(. > 1)", """select(.s == "FR") | .xs[]""", ".xs[] | select(. > 1) | . == 3",
      ".rs[] | .b", ".rs[] | select(.a > 1) | .b", ".rs[] | .ys[]", ".rs[] | .ys[] | select(. > 5)",
      ".rs[] | select(.ys[] > 6) | .b", ".rs[] | .ys[0]", ".rs[] | .a == .ys[0]", ".rs[] | .ys | length",
      ".xss[][]", ".xss[] | .[] | select(. != 2)", ".xss[] | length", ".rs[] | .xs",
      ".xs[] > 1", ".xs[] > .xs[]", "select(.xs[] > 2) | .s", "select(.xs[] > 2) | .xs[]", ".xs[] | .a"
    )
    // an undecodable record emits nothing, even when some elements were emitted before the
    // corruption is found; corruption after the last field read is not seen (see the README)
    programs.foreach(p => check(schema, batch(good ++ (if p.contains(".rs") then corrupted else corrupted.take(1))), p))
  }

  test("values emitted whole, written from the bytes: every type, escapes, ill-formed UTF-8, odd maps") {
    import Hand.*
    val schema = AvroFixtures.schema("""
      {"type": "record", "name": "Top", "fields": [
        {"name": "r", "type": {"type": "record", "name": "All", "fields": [
          {"name": "f", "type": "float"},
          {"name": "d", "type": "double"},
          {"name": "s", "type": "string"},
          {"name": "m", "type": {"type": "map", "values": "string"}},
          {"name": "mm", "type": {"type": "map", "values": {"type": "map", "values": "int"}}},
          {"name": "u", "type": ["null", "int", "string", {"type": "array", "items": ["null", "long"]}]},
          {"name": "e", "type": {"type": "enum", "name": "E", "symbols": ["A", "B_c"]}},
          {"name": "b", "type": "bytes"},
          {"name": "x", "type": {"type": "fixed", "name": "X", "size": 3}},
          {"name": "dec", "type": {"type": "bytes", "logicalType": "decimal", "precision": 6, "scale": 2}},
          {"name": "xs", "type": {"type": "array", "items":
            {"type": "record", "name": "P", "fields": [{"name": "a", "type": "int"}, {"name": "t", "type": "string"}]}}},
          {"name": "l", "type": "long"},
          {"name": "big", "type": {"type": "map", "values": "boolean"}},
          {"name": "empty", "type": {"type": "record", "name": "Empty", "fields": []}}]}},
        {"name": "n", "type": "int"}]}""")
    def float(x: Float)   = { val i = java.lang.Float.floatToRawIntBits(x); Array.tabulate(4)(k => (i >>> (8 * k)).toByte) }
    def double(x: Double) = { val l = java.lang.Double.doubleToRawLongBits(x); Array.tabulate(8)(k => (l >>> (8 * k)).toByte) }
    def raw(bs: Array[Byte]) = long(bs.length) ++ bs
    def hex(h: String)       = h.split(' ').map(Integer.parseInt(_, 16).toByte)
    def map(entries: (Array[Byte], Array[Byte])*) =
      if entries.isEmpty then long(0) else long(entries.size) ++ entries.flatMap((k, v) => raw(k) ++ v) ++ long(0)
    def k(s: String) = s.getBytes(UTF_8)
    def all(
        f: Float = 1.5f, d: Double = 0.1, s: Array[Byte] = k("john"), m: Array[Byte] = map(k("a") -> str("x")),
        mm: Array[Byte] = map(k("o") -> map(k("i") -> long(1))), u: Array[Byte] = long(0), e: Int = 1,
        xs: Array[Byte] = long(1) ++ long(7) ++ str("p") ++ long(0), l: Long = 42L, big: Array[Byte] = long(0)
    ) =
      float(f) ++ double(d) ++ raw(s) ++ m ++ mm ++ u ++ long(e) ++ raw(hex("00 FF 10")) ++ hex("41 42 43") ++
        raw(BigInt(-12345).toByteArray) ++ xs ++ long(l) ++ big
    val bigMap = map((1 to 70).map(i => k(s"k$i") -> Array[Byte]((i % 2).toByte))*)
    val payloads = List(
      all(),
      all(f = Float.NaN, d = -0.0, s = k("\u0000\n\"\\\u007f\u0085\u00e9\u2028😀/"), m = map(), u = long(2) ++ str("é"), e = 0),
      all(f = 1e10f, d = 1e22, s = hex("E9 41"), m = map(k("ok") -> str("v"), hex("FF") -> str("w")), u = long(3) ++ long(2) ++ long(0) ++ long(1) ++ long(-5) ++ long(0)),
      all(f = Float.NegativeInfinity, d = Double.MinPositiveValue, m = map(k("a") -> str("1"), k("b") -> str("2"), k("a") -> str("3"))),
      all(mm = map(k("o") -> map(k("i") -> long(1), k("i") -> long(2)), k("p") -> map(k("i") -> long(3))), big = bigMap),
      // sized blocks in a map and in an array of records; a map of maps with the same inner keys
      all(m = { val body = raw(k("a")) ++ str("1") ++ raw(k("b")) ++ str("2"); long(-2) ++ long(body.length) ++ body ++ long(1) ++ raw(k("c")) ++ str("3") ++ long(0) },
        mm = map(k("o") -> map(k("i") -> long(1)), k("p") -> map(k("i") -> long(2))),
        xs = { val body = long(1) ++ str("p") ++ long(2) ++ str("q"); long(-2) ++ long(body.length) ++ body ++ long(0) }, l = Long.MinValue),
      all(u = long(1) ++ long(-7), l = Long.MaxValue, d = Double.NaN)
    ).map(_ ++ long(5))
    // corrupted records, each seen by the programs that read that far (what is skipped is checked
    // for its structure only, nothing after the last value read is: see the README)
    val truncated = all().take(20) // in m: f (4 bytes), d (8), s (5), then m
    val badUnion  = all(u = long(9)) ++ long(5)
    val badEnum   = all(e = 7) ++ long(5) // seen only when the enum is written
    // corrupted first: a write cut by an error must leave nothing for the next one
    def batch(corrupted: Array[Byte]*) =
      (corrupted.toList ++ payloads).zipWithIndex.map((v, i) => Record("t", 0, i.toLong, 0L, "k", Confluent.frame(1, v))).toVector
    // the very same bytes
    List(".r.u", ".r.xs", ".r.empty", ".r.b", ".r.x", ".r.dec", ".r.xs[]").foreach(check(schema, batch(truncated, badUnion), _))
    check(schema, batch(truncated), ".r.mm.p")
    // the same JSON: the reference gives the keys of a map in hash order, the engine in the order
    // of the payload; a key written twice by the engine has the last value, as in the reference
    List(".r.m", ".r.mm", ".r | .mm", ".r.mm.o", """select(.r.m.a == "x") | .r.m""").foreach(checkMeaning(schema, batch(truncated), _))
    checkMeaning(schema, batch(truncated, badUnion), ".r.big")
    List(".r", "select(.n > 0) | .r", "select(.r.l > 0) | .r").foreach(checkMeaning(schema, batch(truncated, badUnion, badEnum), _))
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
    for engine <- List(Engine.Planned) do
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
