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
    val slots        = planner.slots.size
    val hasRefs      = planner.slots.contains(Plan.SlotType.Json)
    val c            = Closures(penv)
    val run          = c.node(program, Some(Nil))
    (record, out) =>
      val bytes = record.value
      val f     = Frame(bytes, new Array[Long](slots), if hasRefs then new Array[AnyRef](slots) else null)
      read(AvroCursor(bytes, Confluent.HeaderSize), f)
      try
        run(f, Root, v => if v eq Root then out += record else out += record.copy(value = c.json(v, f).noSpaces.getBytes(UTF_8)))
      catch case _: JqError => () // the record is skipped, outputs already emitted are kept

  // ---------------------------------------------------------------------------- slots

  /** The slots of one record: longs, doubles (raw bits), ints, booleans in `nums`, JSON in `refs`. */
  final class Frame(val bytes: Array[Byte], val nums: Array[Long], val refs: Array[AnyRef]):
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

  trait Sink:
    def apply(v: AnyRef): Unit

  trait Node:
    def apply(f: Frame, in: AnyRef, k: Sink): Unit

  type Cond = Frame => Boolean

  private enum Operand:
    case Lit(value: Json)
    case At(value: PVal)

  import PVal.*

  private final class Closures(penv: Map[Path, PVal]):

    private val materializers = penv.map((p, v) => p -> materialize(v))

    /** `v` as a `Json`. */
    def json(v: AnyRef, f: Frame): Json = v match
      case j: Json => j
      case Undecoded(p) =>
        materializers.get(p) match
          case Some(m) => m(f)
          case None    => throw IllegalStateException(s"internal error: path .${p.mkString(".")} was not decoded")

    def node(p: Jq, in: Option[Path]): Node =
      boolValue(p, in) match
        case Some(c) => (f, _, k) => k(Runtime.bool(c(f)))
        case None    => generic(p, in)

    private def generic(p: Jq, in: Option[Path]): Node = p match
      case Jq.Identity   => (_, v, k) => k(v)
      case Jq.Literal(j) => (_, _, k) => k(j)
      case Jq.Empty      => (_, _, _) => ()
      case Jq.Not        => (f, v, k) => k(Runtime.bool(!Runtime.truthy(json(v, f))))
      case Jq.Length     => (f, v, k) => k(Runtime.length(json(v, f)))

      case Jq.Field(t, name) =>
        staticPath(p, in) match
          case Some(path) =>
            materializers.get(path) match
              case Some(m) => (f, _, k) => k(m(f))
              case None    => val u = Undecoded(path); (_, _, k) => k(u) // only navigated through
          case None =>
            val tn = node(t, in)
            (f, v, k) => tn(f, v, x => k(Runtime.field(json(x, f), name)))
      case Jq.Index(t, i) =>
        val tn = node(t, in)
        (f, v, k) => tn(f, v, x => k(Runtime.index(json(x, f), i)))
      case Jq.Iterate(t) =>
        val tn = node(t, in)
        (f, v, k) =>
          tn(f, v, x =>
            val it = Runtime.iterate(json(x, f))
            while it.hasNext do k(it.next()))

      case Jq.Pipe(l, r) =>
        val (ln, rn) = (node(l, in), node(r, pathOf(l, in)))
        (f, v, k) => ln(f, v, x => rn(f, x, k))

      case Jq.Compare(op, l, r) =>
        // jq iterates over the right operand in the outer loop
        val (ln, rn) = (node(l, in), node(r, in))
        (f, v, k) => rn(f, v, b => ln(f, v, a => k(Runtime.bool(op.test(Runtime.compare(json(a, f), json(b, f)))))))

      case Jq.And(l, r) => logical(l, r, in, shortCircuitOn = false)
      case Jq.Or(l, r)  => logical(l, r, in, shortCircuitOn = true)

      case Jq.Select(c) =>
        cond(c, in).filter(_ => singleOutput(c)) match
          case Some(cf) => (f, v, k) => if cf(f) then k(v)
          case None =>
            val cn = node(c, in)
            (f, v, k) => cn(f, v, x => if Runtime.truthy(json(x, f)) then k(v))

      case Jq.Test(Jq.Literal(j)) if j.isString =>
        val re = Pattern.compile(j.asString.get)
        (f, v, k) => k(Runtime.bool(Runtime.test(json(v, f), re)))
      case Jq.Test(re) =>
        val rn = node(re, in)
        (f, v, k) => rn(f, v, x => k(Runtime.bool(Runtime.test(json(v, f), Runtime.compileRegex(json(x, f))))))
      case Jq.StartsWith(prefix) =>
        val pn = node(prefix, in)
        (f, v, k) => pn(f, v, x => k(Runtime.bool(Runtime.startsWith(json(v, f), json(x, f)))))
      case Jq.EndsWith(suffix) =>
        val sn = node(suffix, in)
        (f, v, k) => sn(f, v, x => k(Runtime.bool(Runtime.endsWith(json(v, f), json(x, f)))))

    private def logical(l: Jq, r: Jq, in: Option[Path], shortCircuitOn: Boolean): Node =
      val (ln, rn) = (node(l, in), node(r, in))
      (f, v, k) =>
        ln(f, v, a =>
          if Runtime.truthy(json(a, f)) == shortCircuitOn then k(Runtime.bool(shortCircuitOn))
          else rn(f, v, b => k(Runtime.bool(Runtime.truthy(json(b, f))))))

    // ------------------------------------------------- booleans, without going through Json

    /** `p` yields exactly one boolean, computed straight from the slots. */
    private def boolValue(p: Jq, in: Option[Path]): Option[Cond] = p match
      case Jq.Compare(op, l, r) =>
        (operand(l, in), operand(r, in)) match
          case (Some(Operand.Lit(a)), Some(Operand.Lit(b))) => Some(const(Runtime.compareOp(op, a, b)))
          case (Some(Operand.At(v)), Some(Operand.Lit(y)))  => Some(compare(v, op, y))
          case (Some(Operand.Lit(y)), Some(Operand.At(v)))  => Some(compare(v, op.flip, y))
          case _                                            => None
      case Jq.And(l, r) =>
        for a <- cond(l, in); b <- cond(r, in) yield (f: Frame) => a(f) && b(f)
      case Jq.Or(l, r) =>
        for a <- cond(l, in); b <- cond(r, in) yield (f: Frame) => a(f) || b(f)
      case Jq.Pipe(x, Jq.Not) => cond(x, in).map(c => (f: Frame) => !c(f))
      case Jq.Not             => cond(Jq.Identity, in).map(c => (f: Frame) => !c(f))
      case _                  => None

    /** The truthiness of `p`'s only output, straight from the slots. */
    private def cond(p: Jq, in: Option[Path]): Option[Cond] =
      boolValue(p, in).orElse(operand(p, in).map {
        case Operand.Lit(j) => const(Runtime.truthy(j))
        case Operand.At(v)  => truthy(v)
      })

    private def operand(p: Jq, in: Option[Path]): Option[Operand] = p match
      case Jq.Literal(j) => Some(Operand.Lit(j))
      case _             => staticPath(p, in).flatMap(penv.get).map(Operand.At(_))

    private def const(b: Boolean): Cond = if b then _ => true else _ => false

    /** `v op y`, specialised on the type of `v`'s slot. */
    private def compare(v: PVal, op: CmpOp, y: Json): Cond =
      def byRank(rank: Int): Cond = const(op.test(Integer.compare(rank, Runtime.rank(y))))
      v match
        case PStatic(j) => const(Runtime.compareOp(op, j, y))
        case PLong(s) =>
          if y.isNumber then
            val d = y.asNumber.get.toDouble
            f => op.test(Runtime.compareDoubles(f.nums(s).toDouble, d))
          else byRank(Runtime.NumberRank)
        case PDouble(s, _) =>
          // NaN and infinities are null in the JSON view
          val whenNull = op.test(Integer.compare(Runtime.NullRank, Runtime.rank(y)))
          if y.isNumber then
            val d = y.asNumber.get.toDouble
            f =>
              val x = f.double(s)
              if java.lang.Double.isFinite(x) then op.test(Runtime.compareDoubles(x, d)) else whenNull
          else
            val whenNumber = op.test(Integer.compare(Runtime.NumberRank, Runtime.rank(y)))
            if whenNumber == whenNull then const(whenNumber)
            else f => java.lang.Double.isFinite(f.double(s)) == whenNumber
        case PBoolean(s) =>
          val (t, e) = (Runtime.compareOp(op, Json.True, y), Runtime.compareOp(op, Json.False, y))
          f => if f.nums(s) != 0 then t else e
        case PString(off, len) =>
          y.asString match
            case Some(str) =>
              val lit = str.getBytes(UTF_8)
              op match
                case CmpOp.Eq  => f => Runtime.utf8Equals(f.bytes, f.int(off), f.int(len), lit)
                case CmpOp.Neq => f => !Runtime.utf8Equals(f.bytes, f.int(off), f.int(len), lit)
                case _         => f => op.test(Runtime.compareUtf8(f.bytes, f.int(off), f.int(len), lit))
            case None => byRank(Runtime.StringRank)
        case PEnum(s, symbols) =>
          val results = symbols.map(sym => Runtime.compareOp(op, Json.fromString(sym), y)).toArray
          f => results(f.int(s))
        case PChoice(s, branches) =>
          val bs = branches.map(compare(_, op, y)).toArray
          f => bs(f.int(s))(f)
        case PJson(s, rank) =>
          if rank != Runtime.rank(y) then byRank(rank)
          else f => op.test(Runtime.compare(f.refs(s).asInstanceOf[Json], y))
        case PField(_, _) =>
          val m = materialize(v)
          f => op.test(Runtime.compare(m(f), y))

    private def truthy(v: PVal): Cond = v match
      case PStatic(j)                         => const(Runtime.truthy(j))
      case PLong(_) | PString(_, _) | PEnum(_, _) => const(true)
      case PDouble(s, _)                      => f => java.lang.Double.isFinite(f.double(s))
      case PBoolean(s)                        => f => f.nums(s) != 0
      case PJson(_, rank)                     => const(rank > Runtime.FalseRank)
      case PChoice(s, branches) =>
        val bs = branches.map(truthy).toArray
        f => bs(f.int(s))(f)
      case PField(_, _) =>
        val m = materialize(v)
        f => Runtime.truthy(m(f))

    /** The `Json` of a slot: the fallback for anything but comparisons with a literal. */
    private def materialize(v: PVal): Frame => Json = v match
      case PStatic(j)              => _ => j
      case PLong(s)                => f => Json.fromLong(f.nums(s))
      case PDouble(s, true)        => f => Json.fromFloatOrNull(f.double(s).toFloat)
      case PDouble(s, false)       => f => Json.fromDoubleOrNull(f.double(s))
      case PBoolean(s)             => f => Runtime.bool(f.nums(s) != 0)
      case PString(off, len)       => f => Json.fromString(String(f.bytes, f.int(off), f.int(len), UTF_8))
      case PJson(s, _)             => f => f.refs(s).asInstanceOf[Json]
      case PEnum(s, symbols)       =>
        val js = symbols.map(Json.fromString).toArray
        f => js(f.int(s))
      case PChoice(s, branches) =>
        val bs = branches.map(materialize).toArray
        f => bs(f.int(s))(f)
      case PField(parent, name) =>
        val m = materialize(parent)
        f => Runtime.field(m(f), name)
