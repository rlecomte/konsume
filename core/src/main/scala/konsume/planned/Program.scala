package konsume.planned

import io.circe.Json
import konsume.jq.{CmpOp, Jq, JqError, Runtime}
import konsume.json.JsonCursor
import konsume.planned.Syntax.{pathOf, singleOutput, staticPath}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.regex.Pattern

/** A value of the program: a `Json`, or a path of the record that was not decoded (`Root`, the
  * record itself, emitted with its original bytes).
  */
final case class Undecoded(path: Path)
val Root: Undecoded = Undecoded(Nil)

/** Takes one value: the continuation of a node, fixed when the program is built (CPS, as the
  * staged engine had it, but with closures): running the program allocates no closure.
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
  /** the length of a path of the record (`.items | length`) */
  case Len(value: PVal)

import PVal.*

/** The program turned into closures once, on the values `penv` gives for the paths it uses. */
final class Program(penv: Map[Path, PVal]):

  private var nregs = 0
  def registers: Int = nregs
  private def register(): Int =
    nregs += 1
    nregs - 1

  // a path whose length only was read has no value: navigating through it stays `Undecoded`
  private val materializers = penv.collect { case (p, v) if materializable(v) => p -> materialize(v) }

  /** `v` as a `Json`. */
  def json(v: AnyRef, f: Frame): Json = v match
    case j: Json => j
    case Undecoded(p) =>
      materializers.get(p) match
        case Some(m) => m(f)
        case None    => throw IllegalStateException(s"internal error: path ${show(p)} was not decoded")

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
    case Jq.Length =>
      in.flatMap(penv.get) match
        case Some(pv) => val l = lengthJson(pv); (f, _) => k(f, l(f)) // the length of a path of the record
        case None     => (f, v) => k(f, Runtime.length(json(v, f)))

    case Jq.Field(t, name) =>
      staticPath(p, in) match
        case Some(path) => atPath(path, k)
        case None       => node(t, in, (f, x) => k(f, Runtime.field(json(x, f), name)))
    case Jq.Index(t, i) =>
      staticPath(p, in) match
        case Some(path) => atPath(path, k)
        case None       => node(t, in, (f, x) => k(f, Runtime.index(json(x, f), i)))
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

  /** The value at a path of the record, passed to `k`. */
  private def atPath(path: Path, k: Sink): Sink =
    if (k eq emit) && penv.contains(path) then
      // the output of the program: written from the slots, without building a `Json`
      val w = output(penv(path))
      (f, _) => f.out += f.record.copy(value = w(f))
    else
      materializers.get(path) match
        case Some(m) => (f, _) => k(f, m(f))
        case None    => val u = Undecoded(path); (f, _) => k(f, u) // only navigated through

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
        case (Some(Operand.At(a)), Some(Operand.At(b)))   => Some(compareSlots(a, op, b))
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
    // `.items | length > 2` is `.items | (length > 2)`: the right side on the path
    case Jq.Pipe(x, r) if staticPath(x, in).isDefined => boolValue(r, staticPath(x, in))
    case _ => None

  /** The truthiness of `p`'s only output. */
  private def cond(p: Jq, in: Option[Path]): Option[Cond] =
    boolValue(p, in).orElse(operand(p, in).map {
      case Operand.Lit(j) => const(Runtime.truthy(j))
      case Operand.At(v)  => truthy(v)
      case Operand.Input  => (f, v) => Runtime.truthy(json(v, f))
      case Operand.Len(v) => val l = lengthJson(v); (f, _) => Runtime.truthy(l(f))
    })

  private def operand(p: Jq, in: Option[Path]): Option[Operand] = p match
    case Jq.Literal(j)                => Some(Operand.Lit(j))
    case Jq.Identity if in.isEmpty    => Some(Operand.Input)
    case Jq.Pipe(x, Jq.Length)        => staticPath(x, in).flatMap(penv.get).map(Operand.Len(_))
    case Jq.Length                    => in.flatMap(penv.get).map(Operand.Len(_))
    case _                            => staticPath(p, in).flatMap(penv.get).map(Operand.At(_))

  private def const(b: Boolean): Cond = if b then (_, _) => true else (_, _) => false

  /** The value of an operand, as a `Json`. */
  private def value(o: Operand): (Frame, AnyRef) => Json = o match
    case Operand.Lit(j) => (_, _) => j
    case Operand.At(pv) => val m = materialize(pv); (f, _) => m(f)
    case Operand.Input  => (f, v) => json(v, f)
    case Operand.Len(v) => val l = lengthJson(v); (f, _) => l(f)

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
          case Operand.At(pv) => Some(testAt(pv, re, register()))
          case _              => Some((f, v) => Runtime.test(x(f, v), re))
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

  /** `test(re)` on a path of the record. A string still in the payload is matched in place by
    * the frame's [[RegexOnBytes]] (register `r`), other values on their JSON view.
    */
  private def testAt(v: PVal, re: Pattern, r: Int): Cond = v match
    case PString(off, len) =>
      (f, _) =>
        var regex = f.regs(r).asInstanceOf[RegexOnBytes]
        if regex == null then // the first record of this thread
          regex = RegexOnBytes(re)
          f.regs(r) = regex
        regex.find(f.bytes, f.int(off), f.int(len))
    case PChoice(s, bs) =>
      val cs = bs.map(testAt(_, re, r)).toArray
      (f, x) => cs(f.int(s))(f, x)
    case _ =>
      val m = materialize(v)
      (f, _) => Runtime.test(m(f), re)

  /** Does the string `bytes[off, off + len)` start (or end) with `lit`? */
  private def bytesAt(bytes: Array[Byte], off: Int, len: Int, lit: Array[Byte], atEnd: Boolean): Boolean =
    lit.length <= len && {
      val from = if atEnd then off + len - lit.length else off
      java.util.Arrays.equals(bytes, from, from + lit.length, lit, 0, lit.length)
    }

  /** `o op y`. */
  private def compare(o: Operand, op: CmpOp, y: Json): Cond = o match
    case Operand.At(pv) => compareSlot(pv, op, y)
    case Operand.Len(v) => compareLength(v, op, y)
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
      case PNumber(s, _, _) =>
        if y.isNumber then
          val d = y.asNumber.get.toDouble
          (f, _) => op.test(Runtime.compareDoubles(f.double(s), d))
        else byRank(Runtime.NumberRank)
      case PText(start, len) =>
        y.asString match
          case Some(lit) =>
            op match
              case CmpOp.Eq  => (f, _) => JsonCursor.unescape(f.bytes, f.int(start), f.int(len)) == lit
              case CmpOp.Neq => (f, _) => JsonCursor.unescape(f.bytes, f.int(start), f.int(len)) != lit
              case _ => (f, _) => op.test(Runtime.compareStrings(JsonCursor.unescape(f.bytes, f.int(start), f.int(len)), lit))
          case None => byRank(Runtime.StringRank)
      case PSlice(_, _, rank) =>
        if rank != Runtime.rank(y) then byRank(rank)
        else
          val m = materialize(v)
          (f, _) => op.test(Runtime.compare(m(f), y))
      case PSized(_, rank, whole) =>
        if rank != Runtime.rank(y) then byRank(rank)
        else compareSlot(whole.getOrElse(notRead(v)), op, y)
      case PFail(message) => (_, _) => throw JqError(message)

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
    case PNumber(_, _, _) | PText(_, _) => const(true)
    case PSlice(_, _, rank)             => const(rank > Runtime.FalseRank)
    case PSized(_, rank, _)             => const(rank > Runtime.FalseRank)
    case PFail(message)                 => (_, _) => throw JqError(message)

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
    case PField(parent, Step.Key(name)) =>
      val m = materialize(parent)
      f => Runtime.field(m(f), name)
    case PField(parent, Step.At(i)) =>
      val m = materialize(parent)
      f => Runtime.index(m(f), i)
    case PNumber(_, start, len) => f => JsonCursor.parse(f.bytes, f.int(start), f.int(len))
    case PText(start, len)      => f => Json.fromString(JsonCursor.unescape(f.bytes, f.int(start), f.int(len)))
    case PSlice(start, len, _)  => f => JsonCursor.parse(f.bytes, f.int(start), f.int(len))
    case PSized(_, _, whole)    => materialize(whole.getOrElse(notRead(v)))
    case PFail(message)         => _ => throw JqError(message)

  private def materializable(v: PVal): Boolean = v match
    case PSized(_, _, None)   => false
    case PChoice(_, branches) => branches.forall(materializable)
    case PField(parent, _)    => materializable(parent)
    case _                    => true

  private def notRead(v: PVal): Nothing =
    throw IllegalStateException(s"internal error: only the length of $v was read")

  // ----------------------------------------------------------- comparing two paths, in place

  /** `a op b`, both paths of the record: strings compared as UTF-8 bytes in place, numbers as
    * doubles, values of different types by their rank. Anything else on their JSON views.
    */
  private def compareSlots(a: PVal, op: CmpOp, b: PVal): Cond =
    (a, b) match
      case (PStatic(x), _) => compareSlot(b, op.flip, x)
      case (_, PStatic(y)) => compareSlot(a, op, y)
      case (PChoice(s, bs), _) =>
        val cs = bs.map(compareSlots(_, op, b)).toArray
        (f, v) => cs(f.int(s))(f, v)
      case (_, PChoice(s, bs)) =>
        val cs = bs.map(compareSlots(a, op, _)).toArray
        (f, v) => cs(f.int(s))(f, v)
      case (PString(ao, al), PString(bo, bl)) =>
        op match
          case CmpOp.Eq  => (f, _) => utf8Equals(f.bytes, f.int(ao), f.int(al), f.int(bo), f.int(bl))
          case CmpOp.Neq => (f, _) => !utf8Equals(f.bytes, f.int(ao), f.int(al), f.int(bo), f.int(bl))
          case _ =>
            (f, _) =>
              val (x, y) = (f.int(ao), f.int(bo))
              op.test(java.util.Arrays.compareUnsigned(f.bytes, x, x + f.int(al), f.bytes, y, y + f.int(bl)))
      case _ =>
        (number(a), number(b), staticRank(a), staticRank(b)) match
          case (Some(x), Some(y), _, _)                => (f, _) => op.test(Runtime.compareDoubles(x(f), y(f)))
          case (_, _, Some(ra), Some(rb)) if ra != rb => const(op.test(Integer.compare(ra, rb)))
          case _ =>
            val (x, y) = (materialize(a), materialize(b))
            (f, _) => op.test(Runtime.compare(x(f), y(f)))

  private def utf8Equals(bytes: Array[Byte], ao: Int, al: Int, bo: Int, bl: Int): Boolean =
    al == bl && java.util.Arrays.equals(bytes, ao, ao + al, bytes, bo, bo + bl)

  /** Reads a double from the frame, without boxing it (a `Frame => Double` would). */
  private trait DoubleAt:
    def apply(f: Frame): Double

  /** Reads a long from the frame, without boxing it. */
  private trait LongAt:
    def apply(f: Frame): Long

  /** The value of a path that is always a number, as a double. */
  private def number(v: PVal): Option[DoubleAt] = v match
    case PLong(s)         => Some(f => f.nums(s).toDouble)
    case PNumber(s, _, _) => Some(f => f.double(s))
    case _                => None

  /** The rank of a path's values in jq's order, when it is always the same. */
  private def staticRank(v: PVal): Option[Int] = v match
    case PLong(_) | PNumber(_, _, _)            => Some(Runtime.NumberRank)
    case PString(_, _) | PText(_, _) | PEnum(_, _) => Some(Runtime.StringRank)
    case PJson(_, rank)                         => Some(rank)
    case PSlice(_, _, rank)                     => Some(rank)
    case PSized(_, rank, _)                     => Some(rank)
    case _                                      => None

  // -------------------------------------------------------------- length, without the value

  /** The length of a path, when it is known without its JSON view: an array or a map counted
    * while reading, a string from its UTF-8 bytes, null.
    */
  private def lengthOf(v: PVal): Option[LongAt] = v match
    case PSized(count, _, _)           => Some(f => f.nums(count))
    case PString(off, len)             => Some(f => codePoints(f.bytes, f.int(off), f.int(len)))
    case PStatic(j) if j.isNull        => Some(_ => 0L)
    case _                             => None

  /** jq's `length` of a path, as a `Json`. */
  private def lengthJson(v: PVal): Frame => Json = v match
    case PChoice(s, bs) =>
      val ls = bs.map(lengthJson).toArray
      f => ls(f.int(s))(f)
    case _ =>
      lengthOf(v) match
        case Some(l) => f => Json.fromLong(l(f))
        case None    => val m = materialize(v); f => Runtime.length(m(f))

  /** `length op y`. */
  private def compareLength(v: PVal, op: CmpOp, y: Json): Cond = v match
    case PChoice(s, bs) =>
      val cs = bs.map(compareLength(_, op, y)).toArray
      (f, x) => cs(f.int(s))(f, x)
    case _ =>
      lengthOf(v) match
        case Some(l) if y.isNumber =>
          val d = y.asNumber.get.toDouble
          (f, _) => op.test(Runtime.compareDoubles(l(f).toDouble, d))
        case Some(_) => const(op.test(Integer.compare(Runtime.NumberRank, Runtime.rank(y))))
        case None    => val l = lengthJson(v); (f, _) => op.test(Runtime.compare(l(f), y))

  /** The number of code points of UTF-8 bytes: the bytes that do not continue a sequence. */
  private def codePoints(bytes: Array[Byte], off: Int, len: Int): Long =
    var n = 0L
    var i = off
    while i < off + len do
      if (bytes(i) & 0xc0) != 0x80 then n += 1
      i += 1
    n

  // ------------------------------------------------------------- output, without the value

  /** The bytes of a path's value emitted as the output, as `noSpaces` would write them: copied
    * from the payload, or written from the slot, when that gives the very same bytes. Anything
    * else through its JSON view.
    */
  private def output(v: PVal): Frame => Array[Byte] =
    def viaJson: Frame => Array[Byte] =
      val m = materialize(v)
      f => m(f).noSpaces.getBytes(UTF_8)
    v match
      case PStatic(j) => val bytes = j.noSpaces.getBytes(UTF_8); _ => bytes.clone()
      case PChoice(s, bs) =>
        val ws = bs.map(output).toArray
        f => ws(f.int(s))(f)
      case PLong(s) => f => Ascii.long(f.nums(s))
      case PBoolean(s) =>
        val (t, e) = ("true".getBytes(UTF_8), "false".getBytes(UTF_8))
        f => if f.nums(s) != 0 then t.clone() else e.clone()
      case PEnum(s, symbols) =>
        val bytes = symbols.map(sym => Json.fromString(sym).noSpaces.getBytes(UTF_8)).toArray
        f => bytes(f.int(s)).clone()
      case PString(off, len) =>
        val slow = viaJson
        f =>
          val (o, n) = (f.int(off), f.int(len))
          if Ascii.plain(f.bytes, o, n) then Ascii.quoted(f.bytes, o, n) else slow(f)
      case PNumber(_, start, len) => // as written: circe keeps the text of a parsed number
        f => java.util.Arrays.copyOfRange(f.bytes, f.int(start), f.int(start) + f.int(len))
      case _ => viaJson

/** Writing JSON output bytes directly. */
private object Ascii:

  /** Printable ASCII, no quote nor backslash: a string written as is between quotes. */
  def plain(bytes: Array[Byte], off: Int, len: Int): Boolean =
    var i = off
    while i < off + len do
      val c = bytes(i)
      if c < 0x20 || c > 0x7e || c == '"' || c == '\\' then return false
      i += 1
    true

  def quoted(bytes: Array[Byte], off: Int, len: Int): Array[Byte] =
    val out = new Array[Byte](len + 2)
    out(0) = '"'
    System.arraycopy(bytes, off, out, 1, len)
    out(len + 1) = '"'
    out

  /** The decimal digits of `x`, as `Long.toString` writes them. */
  def long(x: Long): Array[Byte] =
    if x == Long.MinValue then return x.toString.getBytes(UTF_8)
    var v      = math.abs(x)
    var digits = 1
    var t      = v
    while t >= 10 do
      t /= 10
      digits += 1
    val n   = if x < 0 then digits + 1 else digits
    val out = new Array[Byte](n)
    var i   = n - 1
    while i >= n - digits do
      out(i) = ('0' + (v % 10)).toByte
      v /= 10
      i -= 1
    if x < 0 then out(0) = '-'
    out
