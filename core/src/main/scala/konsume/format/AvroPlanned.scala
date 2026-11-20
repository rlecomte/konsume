package konsume.format

import io.circe.Json
import konsume.Record
import konsume.format.AvroStaged.{Instr, PVal, Planner}
import konsume.format.Plan.Demand
import konsume.jq.{CmpOp, Jq, JqError, Runtime}
import org.apache.avro.Schema

import java.nio.charset.StandardCharsets.UTF_8
import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer

/** PROTOTYPE: the Avro decoder of [[AvroStaged]], without generating bytecode.
  *
  * Same reading plan (the `Planner` of AvroStaged: needed fields into slots, the others skipped,
  * stop after the last needed one), but run by a small interpreter instead of being compiled;
  * the jq program is turned into closures once, with comparisons specialised on the type of the
  * slot they read (a long, UTF-8 bytes in place, a union index...). No staging compiler: building
  * a program takes microseconds and loads no class.
  *
  * The paths the program uses are found on its syntax tree (`demandOf`), not by running the code
  * generator in recording mode.
  */
object AvroPlanned:

  type Fn   = AvroStaged.Fn
  type Path = List[String]

  def compile(program: Jq, schema: Schema): Fn =
    val schemas      = ArrayBuffer.empty[Schema]
    val planner      = Planner(schemas)
    val (plan, penv) = planner.value(schema, Nil, demandOf(program), needEnd = false)
    val read         = steps(plan, schemas.toVector)
    val c            = Closures(penv)
    val run          = c.node(program, Some(Nil), c.emit)
    val (slots, regs) = (planner.slots.size, c.registers)
    // one frame per thread, reused from record to record: no allocation per record
    val frames = ThreadLocal.withInitial(() => Frame(slots, regs))
    (record, out) =>
      val f = frames.get()
      f.reset(record, out)
      try
        read(f.cur, f)
        run(f, Root)
      catch case _: JqError => () // the record is skipped, outputs already emitted are kept
      finally f.clear()            // the frame outlives the batch: keep no record nor output alive

  // ---------------------------------------------------------------------------- slots

  /** What the program needs while it runs on one record: the slots of the reading plan (longs,
    * doubles as raw bits, ints, booleans in `nums`, JSON in `refs`), the registers of the program
    * (values a node needs again after its operands ran), where to emit.
    */
  final class Frame(slots: Int, registers: Int):
    val nums                                        = new Array[Long](slots)
    val refs                                        = new Array[AnyRef](slots)
    val regs                                        = new Array[AnyRef](registers)
    val cur                                         = AvroCursor(null, 0)
    var bytes: Array[Byte]                          = null
    var record: Record[Any, Array[Byte]]            = null
    var out: ArrayBuffer[Record[Any, Array[Byte]]] = null

    def reset(r: Record[Any, Array[Byte]], o: ArrayBuffer[Record[Any, Array[Byte]]]): Unit =
      record = r
      out = o
      bytes = r.value
      cur.bytes = bytes
      cur.pos = Confluent.HeaderSize

    def clear(): Unit =
      record = null
      out = null
      bytes = null
      cur.bytes = null

    inline def double(s: Int): Double = java.lang.Double.longBitsToDouble(nums(s))
    inline def int(s: Int): Int       = nums(s).toInt

  // ---------------------------------------------------------------------- reading plan

  trait Step:
    def apply(cur: AvroCursor, f: Frame): Unit

  private def steps(is: List[Instr], schemas: Vector[Schema]): Step =
    val all = is.map(step(_, schemas)).toArray
    if all.length == 1 then all(0)
    else
      (cur, f) =>
        var i = 0
        while i < all.length do
          all(i)(cur, f)
          i += 1

  import Instr.*

  private def step(i: Instr, schemas: Vector[Schema]): Step = i match
    case ReadLong(s)    => (cur, f) => f.nums(s) = cur.readLong()
    case ReadFloat(s)   => (cur, f) => f.nums(s) = java.lang.Double.doubleToRawLongBits(cur.readFloat().toDouble)
    case ReadDouble(s)  => (cur, f) => f.nums(s) = java.lang.Double.doubleToRawLongBits(cur.readDouble())
    case ReadBoolean(s) => (cur, f) => f.nums(s) = if cur.readBoolean() then 1L else 0L
    case ReadIndex(s)   => (cur, f) => f.nums(s) = cur.readInt()
    case ReadString(off, len) =>
      (cur, f) =>
        val n = cur.readLength()
        f.nums(off) = cur.pos
        f.nums(len) = n
        cur.skip(n)
    case ReadJson(s, k) => val schema = schemas(k); (cur, f) => f.refs(s) = AvroRuntime.readJson(cur, schema)
    case Skip(schema)   => skip(schema)
    case Branch(s, bs) =>
      val branches = bs.map(steps(_, schemas)).toArray
      (cur, f) =>
        val i = f.int(s)
        if i < 0 || i >= branches.length then throw DecodeError("invalid union index")
        branches(i)(cur, f)

  /** Fixed-size and string skips inline; anything bigger by the generic schema walker. */
  private def skip(schema: Schema): Step =
    import Schema.Type.*
    schema.getType match
      case NULL                => (_, _) => ()
      case BOOLEAN             => (cur, _) => cur.skip(1)
      case INT | LONG | ENUM   => (cur, _) => cur.skipVarint()
      case FLOAT               => (cur, _) => cur.skip(4)
      case DOUBLE              => (cur, _) => cur.skip(8)
      case STRING | BYTES      => (cur, _) => cur.skip(cur.readLength())
      case FIXED               => val n = schema.getFixedSize; (cur, _) => cur.skip(n)
      case _                   => (cur, _) => AvroRuntime.skip(cur, schema)

  // -------------------------------------------------------------------------------- demand

  /** The paths `program` uses, from its syntax: the static paths whose value is consumed by an
    * operation (a comparison, a function, an emitted output), not only navigated through.
    * Emitting the record itself unchanged does not use it.
    */
  def demandOf(program: Jq): Demand =
    val output = pathOf(program, Some(Nil)).filter(_.nonEmpty).toList
    Demand.of(uses(program, Some(Nil)) ++ output)

  private def consume(p: Jq, in: Option[Path]): List[Path] = uses(p, in) ++ pathOf(p, in).toList

  private def uses(p: Jq, in: Option[Path]): List[Path] = p match
    case Jq.Identity | Jq.Literal(_) | Jq.Empty => Nil
    case Jq.Not | Jq.Length                     => in.toList
    case Jq.Field(t, _)                         => if staticPath(p, in).isDefined then Nil else consume(t, in)
    case Jq.Index(t, _)                         => consume(t, in)
    case Jq.Iterate(t)                          => consume(t, in)
    case Jq.Pipe(l, r)                          => uses(l, in) ++ uses(r, pathOf(l, in))
    case Jq.Compare(_, l, r)                    => consume(l, in) ++ consume(r, in)
    case Jq.And(l, r)                           => consume(l, in) ++ consume(r, in)
    case Jq.Or(l, r)                            => consume(l, in) ++ consume(r, in)
    case Jq.Select(c)                           => consume(c, in)
    case Jq.Test(Jq.Literal(_))                 => in.toList
    case Jq.Test(x)                             => in.toList ++ consume(x, in)
    case Jq.StartsWith(x)                       => in.toList ++ consume(x, in)
    case Jq.EndsWith(x)                         => in.toList ++ consume(x, in)

  /** The static path of `p`'s output, given that of its input (`select` keeps it). */
  private def pathOf(p: Jq, in: Option[Path]): Option[Path] = p match
    case Jq.Identity    => in
    case Jq.Select(_)   => in
    case Jq.Field(t, n) => pathOf(t, in).map(_ :+ n)
    case Jq.Pipe(l, r)  => pathOf(r, pathOf(l, in))
    case _              => None

  /** The path of `p` if it is only made of field accesses: then it can be looked up instead of
    * evaluated (unlike `select(c).a`, whose `select` must run).
    */
  private def staticPath(p: Jq, in: Option[Path]): Option[Path] = p match
    case Jq.Identity    => in
    case Jq.Field(t, n) => staticPath(t, in).map(_ :+ n)
    case Jq.Pipe(l, r)  => staticPath(r, staticPath(l, in))
    case _              => None

  private def singleOutput(p: Jq): Boolean = p match
    case Jq.Iterate(_) | Jq.Select(_) | Jq.Empty                       => false
    case Jq.Identity | Jq.Literal(_) | Jq.Not | Jq.Length              => true
    case Jq.Field(t, _)                                                 => singleOutput(t)
    case Jq.Index(t, _)                                                 => singleOutput(t)
    case Jq.Pipe(l, r)                                                  => singleOutput(l) && singleOutput(r)
    case Jq.Compare(_, l, r)                                            => singleOutput(l) && singleOutput(r)
    case Jq.And(l, r)                                                   => singleOutput(l) && singleOutput(r)
    case Jq.Or(l, r)                                                    => singleOutput(l) && singleOutput(r)
    case Jq.Test(x)                                                     => singleOutput(x)
    case Jq.StartsWith(x)                                               => singleOutput(x)
    case Jq.EndsWith(x)                                                 => singleOutput(x)

  // ------------------------------------------------------------------------- the program

  /** A value of the program: a `Json`, or a path of the record that was not decoded (`Root`, the
    * record itself, emitted with its original bytes).
    */
  final case class Undecoded(path: Path)
  private val Root = Undecoded(Nil)

  /** Takes one value: the continuation of a node, fixed when the program is built (CPS, as in
    * `Gen`, but with closures): running the program allocates no closure.
    */
  trait Sink:
    def apply(f: Frame, v: AnyRef): Unit

  /** A condition on the slots and the current input. */
  trait Cond:
    def apply(f: Frame, v: AnyRef): Boolean

  private enum Operand:
    case Lit(value: Json)
    /** a path of the record, read into slots */
    case At(value: PVal)
    /** the current input, a computed value (`.items[] | select(. > 50)`) */
    case Input

  import PVal.*

  private final class Closures(penv: Map[Path, PVal]):

    private var nregs = 0
    def registers: Int = nregs
    private def register(): Int =
      nregs += 1
      nregs - 1

    private val materializers = penv.map((p, v) => p -> materialize(v))

    /** `v` as a `Json`. */
    def json(v: AnyRef, f: Frame): Json = v match
      case j: Json => j
      case Undecoded(p) =>
        materializers.get(p) match
          case Some(m) => m(f)
          case None    => throw IllegalStateException(s"internal error: path .${p.mkString(".")} was not decoded")

    val emit: Sink = (f, v) =>
      if v eq Root then f.out += f.record
      else f.out += f.record.copy(value = json(v, f).noSpaces.getBytes(UTF_8))

    /** `p` on its input, each output passed to `k`. */
    def node(p: Jq, in: Option[Path], k: Sink): Sink =
      boolValue(p, in) match
        case Some(c) => (f, v) => k(f, Runtime.bool(c(f, v)))
        case None    => generic(p, in, k)

    private def generic(p: Jq, in: Option[Path], k: Sink): Sink = p match
      case Jq.Identity   => k
      case Jq.Literal(j) => (f, _) => k(f, j)
      case Jq.Empty      => (_, _) => ()
      case Jq.Not        => (f, v) => k(f, Runtime.bool(!Runtime.truthy(json(v, f))))
      case Jq.Length     => (f, v) => k(f, Runtime.length(json(v, f)))

      case Jq.Field(t, name) =>
        staticPath(p, in) match
          case Some(path) =>
            materializers.get(path) match
              case Some(m) => (f, _) => k(f, m(f))
              case None    => val u = Undecoded(path); (f, _) => k(f, u) // only navigated through
          case None => node(t, in, (f, x) => k(f, Runtime.field(json(x, f), name)))
      case Jq.Index(t, i) => node(t, in, (f, x) => k(f, Runtime.index(json(x, f), i)))
      case Jq.Iterate(t) =>
        node(t, in, (f, x) =>
          val it = Runtime.iterate(json(x, f))
          while it.hasNext do k(f, it.next()))

      case Jq.Pipe(l, r) => node(l, in, node(r, pathOf(l, in), k))

      case Jq.Compare(op, l, r) =>
        // jq iterates over the right operand in the outer loop
        val (input, right) = (register(), register())
        val ln = node(l, in, (f, a) => k(f, Runtime.bool(op.test(Runtime.compare(json(a, f), json(f.regs(right), f))))))
        val rn = node(r, in, (f, b) => { f.regs(right) = b; ln(f, f.regs(input)) })
        (f, v) => { f.regs(input) = v; rn(f, v) }

      case Jq.And(l, r) => logical(l, r, in, shortCircuitOn = false, k)
      case Jq.Or(l, r)  => logical(l, r, in, shortCircuitOn = true, k)

      case Jq.Select(c) =>
        cond(c, in).filter(_ => singleOutput(c)) match
          case Some(cf) => (f, v) => if cf(f, v) then k(f, v)
          case None =>
            val input = register()
            val cn    = node(c, in, (f, x) => if Runtime.truthy(json(x, f)) then k(f, f.regs(input)))
            (f, v) => { f.regs(input) = v; cn(f, v) }

      case Jq.Test(Jq.Literal(j)) if j.isString =>
        val re = Pattern.compile(j.asString.get)
        (f, v) => k(f, Runtime.bool(Runtime.test(json(v, f), re)))
      case Jq.Test(re) =>
        withInput(re, in)((f, v, x) => k(f, Runtime.bool(Runtime.test(json(v, f), Runtime.compileRegex(json(x, f))))))
      case Jq.StartsWith(prefix) =>
        withInput(prefix, in)((f, v, x) => k(f, Runtime.bool(Runtime.startsWith(json(v, f), json(x, f)))))
      case Jq.EndsWith(suffix) =>
        withInput(suffix, in)((f, v, x) => k(f, Runtime.bool(Runtime.endsWith(json(v, f), json(x, f)))))

    /** `body(input, x)` for each output `x` of `arg` on the input. */
    private def withInput(arg: Jq, in: Option[Path])(body: (Frame, AnyRef, AnyRef) => Unit): Sink =
      val input = register()
      val an    = node(arg, in, (f, x) => body(f, f.regs(input), x))
      (f, v) => { f.regs(input) = v; an(f, v) }

    private def logical(l: Jq, r: Jq, in: Option[Path], shortCircuitOn: Boolean, k: Sink): Sink =
      val input = register()
      val rn    = node(r, in, (f, b) => k(f, Runtime.bool(Runtime.truthy(json(b, f)))))
      val ln = node(l, in, (f, a) =>
        if Runtime.truthy(json(a, f)) == shortCircuitOn then k(f, Runtime.bool(shortCircuitOn))
        else rn(f, f.regs(input)))
      (f, v) => { f.regs(input) = v; ln(f, v) }

    // ------------------------------------------------- booleans, without going through Json

    /** `p` yields exactly one boolean, computed from the slots or the input. */
    private def boolValue(p: Jq, in: Option[Path]): Option[Cond] = p match
      case Jq.Compare(op, l, r) =>
        (operand(l, in), operand(r, in)) match
          case (Some(Operand.Lit(a)), Some(Operand.Lit(b))) => Some(const(Runtime.compareOp(op, a, b)))
          case (Some(o), Some(Operand.Lit(y)))              => Some(compare(o, op, y))
          case (Some(Operand.Lit(y)), Some(o))              => Some(compare(o, op.flip, y))
          case _                                            => None
      case Jq.And(l, r) =>
        for a <- cond(l, in); b <- cond(r, in) yield (f, v) => a(f, v) && b(f, v)
      case Jq.Or(l, r) =>
        for a <- cond(l, in); b <- cond(r, in) yield (f, v) => a(f, v) || b(f, v)
      case Jq.Pipe(x, Jq.Not) => cond(x, in).map(c => (f, v) => !c(f, v))
      case Jq.Not             => cond(Jq.Identity, in).map(c => (f, v) => !c(f, v))
      case Jq.Pipe(x, fn @ (Jq.Test(Jq.Literal(_)) | Jq.StartsWith(Jq.Literal(_)) | Jq.EndsWith(Jq.Literal(_)))) =>
        operand(x, in).flatMap(stringFunction(_, fn))
      case Jq.Test(Jq.Literal(_)) | Jq.StartsWith(Jq.Literal(_)) | Jq.EndsWith(Jq.Literal(_)) =>
        operand(Jq.Identity, in).flatMap(stringFunction(_, p))
      case _ => None

    /** The truthiness of `p`'s only output. */
    private def cond(p: Jq, in: Option[Path]): Option[Cond] =
      boolValue(p, in).orElse(operand(p, in).map {
        case Operand.Lit(j) => const(Runtime.truthy(j))
        case Operand.At(v)  => truthy(v)
        case Operand.Input  => (f, v) => Runtime.truthy(json(v, f))
      })

    private def operand(p: Jq, in: Option[Path]): Option[Operand] = p match
      case Jq.Literal(j)                => Some(Operand.Lit(j))
      case Jq.Identity if in.isEmpty    => Some(Operand.Input)
      case _                            => staticPath(p, in).flatMap(penv.get).map(Operand.At(_))

    private def const(b: Boolean): Cond = if b then (_, _) => true else (_, _) => false

    /** The value of an operand, as a `Json`. */
    private def value(o: Operand): (Frame, AnyRef) => Json = o match
      case Operand.Lit(j) => (_, _) => j
      case Operand.At(pv) => val m = materialize(pv); (f, _) => m(f)
      case Operand.Input  => (f, v) => json(v, f)

    /** `test`, `startswith`, `endswith` with a literal argument. A string still in the payload is
      * matched as UTF-8 bytes (a prefix of the code points is a prefix of the bytes); regexes need
      * it decoded.
      */
    private def stringFunction(o: Operand, fn: Jq): Option[Cond] =
      val x = value(o)
      fn match
        case Jq.Test(Jq.Literal(j)) if j.isString =>
          val re = Pattern.compile(j.asString.get)
          o match
            case Operand.At(PString(off, len)) => Some((f, _) => re.matcher(String(f.bytes, f.int(off), f.int(len), UTF_8)).find())
            case _                             => Some((f, v) => Runtime.test(x(f, v), re))
        case Jq.Test(Jq.Literal(j)) => Some((f, v) => Runtime.test(x(f, v), Runtime.compileRegex(j)))
        case Jq.StartsWith(Jq.Literal(j)) =>
          (o, j.asString) match
            case (Operand.At(PString(off, len)), Some(s)) =>
              val lit = s.getBytes(UTF_8)
              Some((f, _) => bytesAt(f.bytes, f.int(off), f.int(len), lit, atEnd = false))
            case _ => Some((f, v) => Runtime.startsWith(x(f, v), j))
        case Jq.EndsWith(Jq.Literal(j)) =>
          (o, j.asString) match
            case (Operand.At(PString(off, len)), Some(s)) =>
              val lit = s.getBytes(UTF_8)
              Some((f, _) => bytesAt(f.bytes, f.int(off), f.int(len), lit, atEnd = true))
            case _ => Some((f, v) => Runtime.endsWith(x(f, v), j))
        case _ => None

    /** Does the string `bytes[off, off + len)` start (or end) with `lit`? */
    private def bytesAt(bytes: Array[Byte], off: Int, len: Int, lit: Array[Byte], atEnd: Boolean): Boolean =
      lit.length <= len && {
        val from = if atEnd then off + len - lit.length else off
        java.util.Arrays.equals(bytes, from, from + lit.length, lit, 0, lit.length)
      }

    /** `o op y`. */
    private def compare(o: Operand, op: CmpOp, y: Json): Cond = o match
      case Operand.At(pv) => compareSlot(pv, op, y)
      case Operand.Lit(j) => const(Runtime.compareOp(op, j, y))
      case Operand.Input =>
        val yr = Runtime.rank(y)
        if y.isNumber then
          val d = y.asNumber.get.toDouble
          (f, v) =>
            val j = json(v, f)
            val r = Runtime.rank(j)
            if r == Runtime.NumberRank then op.test(Runtime.compareDoubles(j.asNumber.get.toDouble, d))
            else op.test(Integer.compare(r, yr))
        else (f, v) => op.test(Runtime.compare(json(v, f), y))

    /** `v op y`, specialised on the type of `v`'s slot. */
    private def compareSlot(v: PVal, op: CmpOp, y: Json): Cond =
      def byRank(rank: Int): Cond = const(op.test(Integer.compare(rank, Runtime.rank(y))))
      v match
        case PStatic(j) => const(Runtime.compareOp(op, j, y))
        case PLong(s) =>
          if y.isNumber then
            val d = y.asNumber.get.toDouble
            (f, _) => op.test(Runtime.compareDoubles(f.nums(s).toDouble, d))
          else byRank(Runtime.NumberRank)
        case PDouble(s, _) =>
          // NaN and infinities are null in the JSON view
          val whenNull = op.test(Integer.compare(Runtime.NullRank, Runtime.rank(y)))
          if y.isNumber then
            val d = y.asNumber.get.toDouble
            (f, _) =>
              val x = f.double(s)
              if java.lang.Double.isFinite(x) then op.test(Runtime.compareDoubles(x, d)) else whenNull
          else
            val whenNumber = op.test(Integer.compare(Runtime.NumberRank, Runtime.rank(y)))
            if whenNumber == whenNull then const(whenNumber)
            else (f, _) => java.lang.Double.isFinite(f.double(s)) == whenNumber
        case PBoolean(s) =>
          val (t, e) = (Runtime.compareOp(op, Json.True, y), Runtime.compareOp(op, Json.False, y))
          (f, _) => if f.nums(s) != 0 then t else e
        case PString(off, len) =>
          y.asString match
            case Some(str) =>
              val lit = str.getBytes(UTF_8)
              op match
                case CmpOp.Eq  => (f, _) => Runtime.utf8Equals(f.bytes, f.int(off), f.int(len), lit)
                case CmpOp.Neq => (f, _) => !Runtime.utf8Equals(f.bytes, f.int(off), f.int(len), lit)
                case _         => (f, _) => op.test(Runtime.compareUtf8(f.bytes, f.int(off), f.int(len), lit))
            case None => byRank(Runtime.StringRank)
        case PEnum(s, symbols) =>
          val results = symbols.map(sym => Runtime.compareOp(op, Json.fromString(sym), y)).toArray
          (f, _) => results(f.int(s))
        case PChoice(s, branches) =>
          val bs = branches.map(compareSlot(_, op, y)).toArray
          (f, v) => bs(f.int(s))(f, v)
        case PJson(s, rank) =>
          if rank != Runtime.rank(y) then byRank(rank)
          else (f, _) => op.test(Runtime.compare(f.refs(s).asInstanceOf[Json], y))
        case PField(_, _) =>
          val m = materialize(v)
          (f, _) => op.test(Runtime.compare(m(f), y))

    private def truthy(v: PVal): Cond = v match
      case PStatic(j)                             => const(Runtime.truthy(j))
      case PLong(_) | PString(_, _) | PEnum(_, _) => const(true)
      case PDouble(s, _)                          => (f, _) => java.lang.Double.isFinite(f.double(s))
      case PBoolean(s)                            => (f, _) => f.nums(s) != 0
      case PJson(_, rank)                         => const(rank > Runtime.FalseRank)
      case PChoice(s, branches) =>
        val bs = branches.map(truthy).toArray
        (f, v) => bs(f.int(s))(f, v)
      case PField(_, _) =>
        val m = materialize(v)
        (f, _) => Runtime.truthy(m(f))

    /** The `Json` of a slot: the fallback for anything but comparisons with a literal. */
    private def materialize(v: PVal): Frame => Json = v match
      case PStatic(j)        => _ => j
      case PLong(s)          => f => Json.fromLong(f.nums(s))
      case PDouble(s, true)  => f => Json.fromFloatOrNull(f.double(s).toFloat)
      case PDouble(s, false) => f => Json.fromDoubleOrNull(f.double(s))
      case PBoolean(s)       => f => Runtime.bool(f.nums(s) != 0)
      case PString(off, len) => f => Json.fromString(String(f.bytes, f.int(off), f.int(len), UTF_8))
      case PJson(s, _)       => f => f.refs(s).asInstanceOf[Json]
      case PEnum(s, symbols) =>
        val js = symbols.map(Json.fromString).toArray
        f => js(f.int(s))
      case PChoice(s, branches) =>
        val bs = branches.map(materialize).toArray
        f => bs(f.int(s))(f)
      case PField(parent, name) =>
        val m = materialize(parent)
        f => Runtime.field(m(f), name)
