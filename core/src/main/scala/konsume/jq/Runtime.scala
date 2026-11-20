package konsume.jq

import io.circe.{Json, JsonNumber}

import java.util.regex.Pattern

/** Raised when a jq program fails on a given input. Stackless: it is control flow. */
final class JqError(message: String) extends RuntimeException(message, null, false, false)

/** jq semantics shared by the interpreter and by the planned engine.
  *
  * Everything here is a plain static method so the engines can call it and the JIT
  * can inline it.
  */
object Runtime:

  // jq total order on types: null < false < true < numbers < strings < arrays < objects
  final val NullRank   = 0
  final val FalseRank  = 1
  final val TrueRank   = 2
  final val NumberRank = 3
  final val StringRank = 4
  final val ArrayRank  = 5
  final val ObjectRank = 6

  def rank(j: Json): Int =
    if j.isNull then NullRank
    else if j.isBoolean then (if j.asBoolean.get then TrueRank else FalseRank)
    else if j.isNumber then NumberRank
    else if j.isString then StringRank
    else if j.isArray then ArrayRank
    else ObjectRank

  def typeName(j: Json): String =
    if j.isNull then "null"
    else if j.isBoolean then "boolean"
    else if j.isNumber then "number"
    else if j.isString then "string"
    else if j.isArray then "array"
    else "object"

  def truthy(j: Json): Boolean = !(j.isNull || j == Json.False)

  inline def bool(b: Boolean): Json = if b then Json.True else Json.False

  /** jq compares strings by code point, not by UTF-16 unit like `String.compareTo`. */
  def compareStrings(a: String, b: String): Int =
    var i = 0
    var j = 0
    while i < a.length && j < b.length do
      val ca = a.codePointAt(i)
      val cb = b.codePointAt(j)
      if ca != cb then return Integer.compare(ca, cb)
      i += Character.charCount(ca)
      j += Character.charCount(cb)
    Integer.compare(a.length - i, b.length - j)

  def compareNumbers(a: JsonNumber, b: JsonNumber): Int = compareDoubles(a.toDouble, b.toDouble)

  /** Not `java.lang.Double.compare`, for which -0.0 < 0.0 (jq: `-0 == 0`). No NaN in JSON. */
  def compareDoubles(a: Double, b: Double): Int = if a < b then -1 else if a > b then 1 else 0

  /** `bytes[off, off + len)` == `lit`, both UTF-8. */
  def utf8Equals(bytes: Array[Byte], off: Int, len: Int, lit: Array[Byte]): Boolean =
    len == lit.length && java.util.Arrays.equals(bytes, off, off + len, lit, 0, len)

  /** Unsigned byte order of UTF-8 is code point order: jq's string order, without decoding. */
  def compareUtf8(bytes: Array[Byte], off: Int, len: Int, lit: Array[Byte]): Int =
    java.util.Arrays.compareUnsigned(bytes, off, off + len, lit, 0, lit.length)

  /** jq's total order on JSON values. */
  def compare(a: Json, b: Json): Int =
    val ra = rank(a)
    val rb = rank(b)
    if ra != rb then Integer.compare(ra, rb)
    else
      ra match
        case NumberRank => compareNumbers(a.asNumber.get, b.asNumber.get)
        case StringRank => compareStrings(a.asString.get, b.asString.get)
        case ArrayRank =>
          val xs = a.asArray.get
          val ys = b.asArray.get
          val n  = math.min(xs.size, ys.size)
          var i  = 0
          while i < n do
            val c = compare(xs(i), ys(i))
            if c != 0 then return c
            i += 1
          Integer.compare(xs.size, ys.size)
        case ObjectRank =>
          // jq: compare the sorted key sets first, then the values key by key
          val oa = a.asObject.get
          val ob = b.asObject.get
          val ka = oa.keys.toVector.sortWith(compareStrings(_, _) < 0)
          val kb = ob.keys.toVector.sortWith(compareStrings(_, _) < 0)
          val ck = compare(Json.fromValues(ka.map(Json.fromString)), Json.fromValues(kb.map(Json.fromString)))
          if ck != 0 then ck
          else
            ka.iterator
              .map(k => compare(oa(k).get, ob(k).get))
              .find(_ != 0)
              .getOrElse(0)
        case _ => 0 // null, false, true: same rank means equal

  def compareOp(op: CmpOp, a: Json, b: Json): Boolean = op.test(compare(a, b))

  // ------------------------------------------------------------------ paths

  def field(j: Json, name: String): Json =
    val o = j.asObject
    if o.isDefined then
      val v = o.get(name)
      if v.isDefined then v.get else Json.Null
    else if j.isNull then Json.Null
    else throw JqError(s"Cannot index ${typeName(j)} with string \"$name\"")

  def index(j: Json, i: Int): Json =
    val a = j.asArray
    if a.isDefined then
      val xs  = a.get
      val idx = if i < 0 then xs.size + i else i
      if idx >= 0 && idx < xs.size then xs(idx) else Json.Null
    else if j.isNull then Json.Null
    else throw JqError(s"Cannot index ${typeName(j)} with number")

  def iterate(j: Json): Iterator[Json] =
    val a = j.asArray
    if a.isDefined then a.get.iterator
    else
      val o = j.asObject
      if o.isDefined then o.get.values.iterator
      else throw JqError(s"Cannot iterate over ${typeName(j)}")

  // --------------------------------------------------------------- builtins

  def length(j: Json): Json =
    if j.isNull then Json.fromInt(0)
    else if j.isNumber then
      val n = j.asNumber.get
      n.toLong match
        case Some(l) => Json.fromLong(math.abs(l))
        case None    => Json.fromDoubleOrNull(math.abs(n.toDouble))
    else if j.isString then
      val s = j.asString.get
      Json.fromInt(s.codePointCount(0, s.length))
    else if j.isArray then Json.fromInt(j.asArray.get.size)
    else if j.isObject then Json.fromInt(j.asObject.get.size)
    else throw JqError(s"${typeName(j)} has no length")

  def string(j: Json, function: String): String =
    val s = j.asString
    if s.isDefined then s.get
    else throw JqError(s"${typeName(j)} cannot be used with $function()")

  def compileRegex(j: Json): Pattern = Pattern.compile(string(j, "test"))

  def test(input: Json, regex: Pattern): Boolean = regex.matcher(string(input, "test")).find()

  def startsWith(input: Json, prefix: Json): Boolean =
    if input.isString && prefix.isString then input.asString.get.startsWith(prefix.asString.get)
    else throw JqError("startswith() requires string inputs")

  def endsWith(input: Json, suffix: Json): Boolean =
    if input.isString && suffix.isString then input.asString.get.endsWith(suffix.asString.get)
    else throw JqError("endswith() requires string inputs")
