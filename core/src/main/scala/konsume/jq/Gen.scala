package konsume.jq

import io.circe.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.util.regex.Pattern
import scala.collection.mutable
import scala.quoted.*

/** Code generation for jq programs, shared by every payload format.
  *
  *   - continuations are inlined at staging time (CPS), so the whole program is fused into the
  *     loop over the batch: no AST, no closures;
  *   - values carry what is known about them statically (`SVal`), so comparisons with a literal
  *     are specialised on its type, booleans are never boxed, constants are folded;
  *   - literals and regexes are built once, outside the loop.
  *
  * In path mode (Avro), values reachable from the root by a path of fields (`.user.age`) are not
  * computed by the program: they are looked up in `Ctx.env`, filled by a decoder that reads them
  * straight from the bytes. Which paths it must decode is found by a first generation in
  * recording mode, see `Ctx.recorder`.
  */
private[konsume] object Gen:

  type Path = List[String]

  /** What the generated code knows about a value. */
  enum SVal:
    case Static(value: Json)
    case Bool(code: Expr[Boolean])
    /** `isInput`: this is the record's value itself, so emitting it does not copy the record. */
    case Dyn(code: Expr[Json], isInput: Boolean = false)
    /** an integer (Avro int or long) */
    case Num(code: Expr[Long])
    /** an Avro float or double: NaN and infinities are `null` in the JSON view */
    case Dbl(code: Expr[Double], isFloat: Boolean)
    /** a JSON number: compared as a double, emitted as written */
    case Number(value: Expr[Double], asJson: Expr[Json])
    /** a decoded string (from a JSON string with escapes) */
    case Text(code: Expr[String])
    /** a JSON value whose type, hence rank in jq's order, is known (an array, an object...) */
    case Opaque(code: Expr[Json], rank: Int)
    /** a string, still as UTF-8 in the payload */
    case Str(bytes: Expr[Array[Byte]], off: Expr[Int], len: Expr[Int])
    /** one of `branches`, depending on `index` (Avro union or enum) */
    case Choice(index: Expr[Int], branches: Vector[SVal])
    /** an error, raised if and when the value is used */
    case Fail(message: String)
    /** a path from the root that was not decoded: only a field access may use it */
    case PathRef(path: Path)

  import SVal.*

  type K = SVal => Expr[Unit]

  final case class Constants(
      json: Map[Json, Expr[Json]],
      regexes: Map[String, Expr[Pattern]],
      utf8: Map[String, Expr[Array[Byte]]]
  )

  final case class Ctx(
      consts: Constants,
      pathMode: Boolean = false,
      env: Map[Path, SVal] = Map.empty,
      recorder: Option[mutable.Set[Path]] = None
  )

  /** The input of a jq expression: its value and, when it has one, its static path from the root
    * (`.user` in `.user | .age`). In path mode, a field access on an input with a path is looked up
    * in `Ctx.env` instead of computed. Exists only at staging time.
    */
  final case class In(value: SVal, path: Option[Path])

  object In:
    /** The record's value itself. */
    def root(value: SVal): In = In(value, Some(Nil))

  // ---------------------------------------------------------- constants built only once

  /** `val c0 = ...; val c1 = ...; body` */
  def hoist[T: Type](program: Jq)(body: Constants => Expr[T])(using Quotes): Expr[T] =
    val lits = literals(program)
    val jsons   = lits.filterNot(j => j.isNull || j.isBoolean).toList
    val strings = (lits.flatMap(_.asString) ++ fieldNames(program)).toList
    val res     = regexes(program).toList

    def loop(js: List[Json], ss: List[String], rs: List[String], acc: Constants): Expr[T] = (js, ss, rs) match
      case (j :: tail, _, _) =>
        '{ val c = ${ liftJson(j) }; ${ loop(tail, ss, rs, acc.copy(json = acc.json + (j -> 'c))) } }
      case (Nil, s :: tail, _) =>
        '{ val u = ${ Expr(s) }.getBytes(UTF_8); ${ loop(Nil, tail, rs, acc.copy(utf8 = acc.utf8 + (s -> 'u))) } }
      case (Nil, Nil, r :: tail) =>
        '{ val re = Pattern.compile(${ Expr(r) }); ${ loop(Nil, Nil, tail, acc.copy(regexes = acc.regexes + (r -> 're))) } }
      case (Nil, Nil, Nil) => body(acc)

    loop(jsons, strings, res, Constants(Map.empty, Map.empty, Map.empty))

  private def literals(p: Jq): Set[Json] = children(p).flatMap(literals).toSet ++ (p match
    case Jq.Literal(j) => Set(j)
    case _             => Set.empty
  )

  private def fieldNames(p: Jq): Set[String] = children(p).flatMap(fieldNames).toSet ++ (p match
    case Jq.Field(_, n) => Set(n)
    case _              => Set.empty
  )

  private def regexes(p: Jq): Set[String] = children(p).flatMap(regexes).toSet ++ (p match
    case Jq.Test(Jq.Literal(j)) if j.isString => j.asString.toSet
    case _                                    => Set.empty
  )

  def children(p: Jq): List[Jq] = p match
    case Jq.Field(t, _)      => List(t)
    case Jq.Index(t, _)      => List(t)
    case Jq.Iterate(t)       => List(t)
    case Jq.Pipe(l, r)       => List(l, r)
    case Jq.Compare(_, l, r) => List(l, r)
    case Jq.And(l, r)        => List(l, r)
    case Jq.Or(l, r)         => List(l, r)
    case Jq.Select(c)        => List(c)
    case Jq.Test(r)          => List(r)
    case Jq.StartsWith(p)    => List(p)
    case Jq.EndsWith(s)      => List(s)
    case Jq.Identity | Jq.Literal(_) | Jq.Not | Jq.Empty | Jq.Length => Nil

  def liftJson(j: Json)(using Quotes): Expr[Json] =
    j.asString match
      case Some(s) => '{ Json.fromString(${ Expr(s) }) }
      case None =>
        j.asNumber.flatMap(_.toLong) match
          case Some(l) => '{ Json.fromLong(${ Expr(l) }) }
          case None    => '{ io.circe.parser.parse(${ Expr(j.noSpaces) }).fold(throw _, identity) }

  // ---------------------------------------------------------------------- value views

  /** The code that builds `v` as a generic jq value (`io.circe.Json`), whatever the payload format.
    * jq values are JSON values, so this is the fallback whenever `v` is handed to `Runtime`; each call
    * gives up the specialized form (Avro slots, bytes in place) and usually allocates.
    */
  def materialize(v: SVal)(using c: Ctx)(using Quotes): Expr[Json] = v match
    case Static(j) if j.isNull        => '{ Json.Null }
    case Static(j) if j == Json.True  => '{ Json.True }
    case Static(j) if j == Json.False => '{ Json.False }
    case Static(j)                    => c.consts.json.getOrElse(j, liftJson(j))
    case Bool(b)                      => '{ if $b then Json.True else Json.False }
    case Dyn(e, _)                    => e
    case Num(e)                       => '{ Json.fromLong($e) }
    case Number(_, j)                 => j
    case Text(e)                      => '{ Json.fromString($e) }
    case Opaque(e, _)                 => e
    case Dbl(e, true)                 => '{ Json.fromFloatOrNull($e.toFloat) }
    case Dbl(e, false)                => '{ Json.fromDoubleOrNull($e) }
    case Str(b, o, l)                 => '{ Json.fromString(String($b, $o, $l, UTF_8)) }
    case Choice(i, bs)                => chooseGrouped(i, bs.map(b => (groupKey(b), materialize(b))))
    case Fail(msg)                    => '{ throw JqError(${ Expr(msg) }) }
    case PathRef(p) =>
      c.recorder match
        case Some(r) =>
          r += p
          '{ Json.Null } // recording mode: the code is thrown away
        case None => throw IllegalStateException(s"internal error: path .${p.mkString(".")} was not decoded")

  /** Truthiness, known statically (Left) or not (Right). */
  def truthy(v: SVal)(using Ctx, Quotes): Either[Boolean, Expr[Boolean]] = v match
    case Static(j)                 => Left(Runtime.truthy(j))
    case Bool(b)                   => Right(b)
    case Num(_) | Str(_, _, _) | Number(_, _) | Text(_) => Left(true)
    case Opaque(_, rank)                        => Left(rank > Runtime.FalseRank)
    case Dbl(d, _)                 => Right('{ java.lang.Double.isFinite($d) })
    case Fail(msg)                 => Right('{ throw JqError(${ Expr(msg) }) })
    case Choice(i, bs) =>
      bs.map(truthy) match
        case ts if ts.forall(_ == Left(true))  => Left(true)
        case ts if ts.forall(_ == Left(false)) => Left(false)
        case ts => Right(chooseGrouped(i, ts.map(t => (t.left.toOption, boolExpr(t)))))
    case Dyn(_, _) | PathRef(_) => Right('{ Runtime.truthy(${ materialize(v) }) })

  private def boolExpr(t: Either[Boolean, Expr[Boolean]])(using Quotes): Expr[Boolean] = t.fold(Expr(_), identity)

  def boolOf(v: SVal)(using Ctx, Quotes): SVal = truthy(v) match
    case Left(b)  => Static(Runtime.bool(b))
    case Right(b) => Bool(b)

  /** `if i == 0 then b0 else if i == 1 then b1 ... else bn` */
  def choose[T: Type](i: Expr[Int], branches: Vector[Expr[T]])(using Quotes): Expr[T] =
    branches.indices.init.foldRight(branches.last)((k, acc) => '{ if $i == ${ Expr(k) } then ${ branches(k) } else $acc })

  /** Like `choose`, but branches with the same key (a constant, the same error) share one
    * test, and the biggest group is the `else`: a choice between 8 JSON types where only one
    * matters becomes `if (kind == NUMBER) ... else false`.
    */
  def chooseGrouped[T: Type](i: Expr[Int], branches: Vector[(Option[Any], Expr[T])])(using Quotes): Expr[T] =
    val groups = branches.zipWithIndex
      .groupBy((b, k) => b._1.getOrElse(("unique", k)))
      .values
      .map(g => (g.map(_._2).sorted, g.head._1._2))
      .toVector
      .sortBy(_._1.head)
    val default = groups.maxBy(_._1.size)
    groups.filterNot(_ eq default).foldRight(default._2) { case ((ks, e), acc) =>
      val cond = ks.map(k => '{ $i == ${ Expr(k) } }).reduce((a, b) => '{ $a || $b })
      '{ if $cond then $e else $acc }
    }

  /** Branches with equal keys generate the same code. */
  private def groupKey(v: SVal): Option[Any] = v match
    case Static(j) => Some(j)
    case Fail(m)   => Some(("fail", m))
    case _         => None

  // ------------------------------------------------------------------- code generation

  /** The static path from the root of `p`'s output, given that of its input. */
  def pathOf(p: Jq, in: Option[Path]): Option[Path] = p match
    case Jq.Identity    => in
    case Jq.Select(_)   => in
    case Jq.Field(t, n) => pathOf(t, in).map(_ :+ n)
    case Jq.Pipe(l, r)  => pathOf(r, pathOf(l, in))
    case _              => None

  /** Generates `p` applied to `in` (whose value must be cheap to duplicate), calling `k` on each
    * output. `k` is inlined, so it is called at most once per call site: never duplicate it,
    * see `withLocalK`.
    */
  def gen(p: Jq, in: In)(k: K)(using c: Ctx)(using Quotes): Expr[Unit] = p match
    case Jq.Identity   => k(in.value)
    case Jq.Literal(j) => k(Static(j))
    case Jq.Empty      => '{ () }
    case Jq.Not        => k(truthy(in.value).fold(b => Static(Runtime.bool(!b)), b => Bool('{ !$b })))
    case Jq.Length     => k(Dyn('{ Runtime.length(${ materialize(in.value) }) }))

    case Jq.Field(t, name) =>
      pathOf(p, in.path) match
        case Some(path) if c.pathMode => k(c.env.getOrElse(path, PathRef(path)))
        case _ => gen(t, in)(v => k(Dyn('{ Runtime.field(${ materialize(v) }, ${ Expr(name) }) })))
    case Jq.Index(t, i) => gen(t, in)(v => k(Dyn('{ Runtime.index(${ materialize(v) }, ${ Expr(i) }) })))
    case Jq.Iterate(t) =>
      gen(t, in) { v =>
        '{
          val it = Runtime.iterate(${ materialize(v) })
          while it.hasNext do
            val x = it.next()
            ${ k(Dyn('x)) }
        }
      }

    case Jq.Pipe(l, r) => gen(l, in)(v => share(v)(sv => gen(r, In(sv, pathOf(l, in.path)))(k)))

    case Jq.Compare(op, l, r) =>
      // jq iterates over the right operand in the outer loop
      gen(r, in)(rv => share(rv)(b => gen(l, in)(lv => share(lv)(a => k(compare(op, a, b))))))

    case Jq.And(l, r) => logical(l, r, in, shortCircuitOn = false)(k)
    case Jq.Or(l, r)  => logical(l, r, in, shortCircuitOn = true)(k)

    case Jq.Select(cond) =>
      gen(cond, in) { cv =>
        truthy(cv) match
          case Left(true)  => k(in.value)
          case Left(false) => '{ () }
          case Right(b)    => '{ if $b then ${ k(in.value) } }
      }

    case Jq.Test(Jq.Literal(j)) if j.isString =>
      val re = c.consts.regexes(j.asString.get)
      k(Bool('{ Runtime.test(${ materialize(in.value) }, $re) }))
    case Jq.Test(re) =>
      gen(re, in)(rv => k(Bool('{ Runtime.test(${ materialize(in.value) }, Runtime.compileRegex(${ materialize(rv) })) })))

    case Jq.StartsWith(prefix) =>
      gen(prefix, in)(pv => k(Bool('{ Runtime.startsWith(${ materialize(in.value) }, ${ materialize(pv) }) })))
    case Jq.EndsWith(suffix) =>
      gen(suffix, in)(sv => k(Bool('{ Runtime.endsWith(${ materialize(in.value) }, ${ materialize(sv) }) })))

  /** `l and r` (shortCircuitOn = false) / `l or r` (shortCircuitOn = true). */
  private def logical(l: Jq, r: Jq, in: In, shortCircuitOn: Boolean)(k: K)(using
      Ctx,
      Quotes
  ): Expr[Unit] =
    gen(l, in) { lv =>
      truthy(lv) match
        case Left(`shortCircuitOn`) => k(Static(Runtime.bool(shortCircuitOn)))
        case Left(_)                => gen(r, in)(rv => k(boolOf(rv)))
        case Right(lb) if singleOutput(r) =>
          // r yields exactly one value: compute it as a plain boolean, no duplication of k
          val rb: Expr[Boolean] = '{
            var res = false
            ${ gen(r, in)(rv => '{ res = ${ boolExpr(truthy(rv)) } }) }
            res
          }
          k(Bool(if shortCircuitOn then '{ $lb || $rb } else '{ $lb && $rb }))
        case Right(lb) =>
          // r may yield 0 or n values: k is needed in both branches, share it as a local def
          withLocalK(k) { k2 =>
            val short = k2(Static(Runtime.bool(shortCircuitOn)))
            val long  = gen(r, in)(rv => k2(boolOf(rv)))
            if shortCircuitOn then '{ if $lb then $short else $long }
            else '{ if $lb then $long else $short }
          }
    }

  private def withLocalK(k: K)(body: K => Expr[Unit])(using Ctx, Quotes): Expr[Unit] =
    '{
      def localK(x: Json): Unit = ${ k(Dyn('x)) }
      ${ body(v => share(v)(sv => '{ localK(${ materialize(sv) }) })) }
    }

  /** Does `p` produce exactly one output, whatever its input? */
  private def singleOutput(p: Jq): Boolean = p match
    case Jq.Iterate(_) | Jq.Select(_) | Jq.Empty => false
    case other                                   => children(other).forall(singleOutput)

  // ------------------------------------------------------------------------ comparisons

  private def staticCmp(op: CmpOp, rankA: Int, b: Json): SVal =
    Static(Runtime.bool(op.test(Integer.compare(rankA, Runtime.rank(b)))))

  def compare(op: CmpOp, a: SVal, b: SVal)(using c: Ctx)(using Quotes): SVal = (a, b) match
    case (Static(x), Static(y)) => Static(Runtime.bool(Runtime.compareOp(op, x, y)))
    case (Fail(_), _)           => a
    case (_, Fail(_))           => b
    case (Choice(i, bs), _)     => combine(i, bs.map(compare(op, _, b)))
    case (_, Choice(i, bs))     => combine(i, bs.map(compare(op, a, _)))
    case (Static(_), _)         => compare(op.flip, b, a)
    case (Dyn(x, _), Static(y)) => Bool(compareWithLiteral(op, x, y))

    case (Number(x, _), Static(y)) =>
      if y.isNumber then Bool(test(op, '{ Runtime.compareDoubles($x, ${ Expr(y.asNumber.get.toDouble) }) }))
      else staticCmp(op, Runtime.NumberRank, y)

    case (Text(x), Static(y)) =>
      y.asString match
        case Some(lit) =>
          op match
            case CmpOp.Eq  => Bool('{ $x == ${ Expr(lit) } })
            case CmpOp.Neq => Bool('{ $x != ${ Expr(lit) } })
            case _         => Bool(test(op, '{ Runtime.compareStrings($x, ${ Expr(lit) }) }))
        case None => staticCmp(op, Runtime.StringRank, y)

    case (Opaque(_, rank), Static(y)) if rank != Runtime.rank(y) => staticCmp(op, rank, y)

    case (Num(x), Static(y)) =>
      if y.isNumber then Bool(test(op, '{ Runtime.compareDoubles($x.toDouble, ${ Expr(y.asNumber.get.toDouble) }) }))
      else staticCmp(op, Runtime.NumberRank, y)

    case (Dbl(x, _), Static(y)) =>
      // NaN and infinities are null in the JSON view
      val Static(whenNull) = staticCmp(op, Runtime.NullRank, y): @unchecked
      if y.isNumber then
        val d = Expr(y.asNumber.get.toDouble)
        Bool('{
          if java.lang.Double.isFinite($x) then ${ test(op, '{ Runtime.compareDoubles($x, $d) }) }
          else ${ Expr(whenNull == Json.True) }
        })
      else
        val Static(whenNumber) = staticCmp(op, Runtime.NumberRank, y): @unchecked
        if whenNumber == whenNull then Static(whenNumber)
        else Bool('{ java.lang.Double.isFinite($x) == ${ Expr(whenNumber == Json.True) } })

    case (Str(bytes, off, len), Static(y)) =>
      y.asString match
        case Some(s) =>
          val lit = c.consts.utf8.getOrElse(s, '{ ${ Expr(s) }.getBytes(UTF_8) })
          op match
            case CmpOp.Eq  => Bool('{ Runtime.utf8Equals($bytes, $off, $len, $lit) })
            case CmpOp.Neq => Bool('{ !Runtime.utf8Equals($bytes, $off, $len, $lit) })
            case _         => Bool(test(op, '{ Runtime.compareUtf8($bytes, $off, $len, $lit) }))
        case None => staticCmp(op, Runtime.StringRank, y)

    case (Bool(x), Static(y)) if y.isBoolean =>
      val yb = y.asBoolean.get
      op match
        case CmpOp.Eq  => Bool(if yb then x else '{ !$x })
        case CmpOp.Neq => Bool(if yb then '{ !$x } else x)
        case _         => Bool(test(op, '{ Integer.compare(if $x then 2 else 1, ${ Expr(if yb then 2 else 1) }) }))
    case (Bool(_), Static(y)) =>
      // a boolean against another type: the type order decides, statically
      staticCmp(op, Runtime.TrueRank, y)

    case _ => Bool(test(op, '{ Runtime.compare(${ materialize(a) }, ${ materialize(b) }) }))

  /** One boolean per branch of a choice. */
  private def combine(i: Expr[Int], results: Vector[SVal])(using Ctx, Quotes): SVal =
    if results.forall(_.isInstanceOf[Static]) && results.distinct.size == 1 then results.head
    else
      Bool(chooseGrouped(i, results.map { r =>
        val t = truthy(r)
        (t.left.toOption.orElse(groupKey(r)), boolExpr(t))
      }))

  /** `x op literal`, specialised on the literal's type. */
  private def compareWithLiteral(op: CmpOp, x: Expr[Json], y: Json)(using Ctx, Quotes): Expr[Boolean] =
    // when x does not have the literal's type, jq's type order decides
    def byRank(rank: Int): Expr[Boolean] = op match
      case CmpOp.Eq  => Expr(false)
      case CmpOp.Neq => Expr(true)
      case _         => test(op, '{ Integer.compare(Runtime.rank($x), ${ Expr(rank) }) })

    if y.isNumber then
      val d = Expr(y.asNumber.get.toDouble)
      '{
        val n = $x.asNumber
        if n.isDefined then ${ test(op, '{ Runtime.compareDoubles(n.get.toDouble, $d) }) }
        else ${ byRank(Runtime.NumberRank) }
      }
    else if y.isString then
      val s = Expr(y.asString.get)
      '{
        val str = $x.asString
        if str.isDefined then ${
          op match
            case CmpOp.Eq  => '{ str.get == $s }
            case CmpOp.Neq => '{ str.get != $s }
            case _         => test(op, '{ Runtime.compareStrings(str.get, $s) })
        }
        else ${ byRank(Runtime.StringRank) }
      }
    else if y.isNull then
      op match
        case CmpOp.Eq  => '{ $x.isNull }
        case CmpOp.Neq => '{ !$x.isNull }
        case _         => test(op, '{ Integer.compare(Runtime.rank($x), ${ Expr(Runtime.NullRank) }) })
    else if y.isBoolean then
      op match
        case CmpOp.Eq  => '{ $x == ${ materialize(Static(y)) } }
        case CmpOp.Neq => '{ $x != ${ materialize(Static(y)) } }
        case _         => test(op, '{ Integer.compare(Runtime.rank($x), ${ Expr(Runtime.rank(y)) }) })
    else test(op, '{ Runtime.compare($x, ${ materialize(Static(y)) }) })

  /** The comparison operator is known at staging time: no dispatch at runtime. */
  private def test(op: CmpOp, cmp: Expr[Int])(using Quotes): Expr[Boolean] = op match
    case CmpOp.Eq  => '{ $cmp == 0 }
    case CmpOp.Neq => '{ $cmp != 0 }
    case CmpOp.Lt  => '{ $cmp < 0 }
    case CmpOp.Lte => '{ $cmp <= 0 }
    case CmpOp.Gt  => '{ $cmp > 0 }
    case CmpOp.Gte => '{ $cmp >= 0 }

  // ------------------------------------------------------------------------------- utils

  /** Binds `v` to a `val` unless it is already a reference or a constant, so that it can be
    * used several times without being recomputed.
    */
  def share(v: SVal)(k: K)(using Quotes): Expr[Unit] = v match
    // made of references, or of code evaluated only if and when needed
    case Static(_) | Fail(_) | PathRef(_) | Choice(_, _) | Str(_, _, _) | Number(_, _) | Text(_) | Opaque(_, _) => k(v)
    case Dyn(e, _) if isRef(e)    => k(v)
    case Bool(e) if isRef(e)      => k(v)
    case Num(e) if isRef(e)       => k(v)
    case Dbl(e, _) if isRef(e)    => k(v)
    case Dyn(e, isInput)          => '{ val x = $e; ${ k(Dyn('x, isInput)) } }
    case Bool(e)                  => '{ val b = $e; ${ k(Bool('b)) } }
    case Num(e)                   => '{ val n = $e; ${ k(Num('n)) } }
    case Dbl(e, f)                => '{ val d = $e; ${ k(Dbl('d, f)) } }

  private def isRef(e: Expr[?])(using Quotes): Boolean =
    import quotes.reflect.*
    def loop(t: Term): Boolean = t match
      case Inlined(_, Nil, t2)   => loop(t2)
      case Typed(t2, _)          => loop(t2)
      case Ident(_) | Literal(_) => true
      case _                     => false
    loop(e.asTerm)
