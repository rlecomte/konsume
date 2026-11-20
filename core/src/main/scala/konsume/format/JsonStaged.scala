package konsume.format

import io.circe.Json
import konsume.Record
import konsume.format.Plan.*
import konsume.jq.{Gen, Jq, JqError, StagingCompiler}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer
import scala.quoted.*

/** Compiles a jq program to code that reads JSON payloads directly: no JSON tree.
  *
  * Like [[AvroStaged]], the paths the program uses are found first, then the payload is scanned
  * once: at each level of interest, keys are compared as bytes with the expected names, the
  * values of the others are skipped without being decoded. A value used by the program is kept
  * as its kind (null, false, true, number, string, array, object) plus a double for numbers, or
  * its position in the payload. The comparisons are generated for every possible kind.
  *
  * JSON specifics:
  *   - the whole payload is read and validated, as the reference decoder does;
  *   - duplicate keys: the last one wins, as in jq (fields under a key seen again are reset);
  *   - strings with escapes are decoded when used, the others are compared as bytes;
  *   - numbers are compared as doubles, but emitted as written.
  */
object JsonStaged:

  type Fn = (Record[Any, Array[Byte]], ArrayBuffer[Record[Any, Array[Byte]]]) => Unit

  private val cache = ConcurrentHashMap[Jq, Fn]()

  def compile(program: Jq): Fn = cache.computeIfAbsent(program, p => StagingCompiler.run(code(p)))

  /** The generated code, for debugging. */
  def show(program: Jq): String = StagingCompiler.show(code(program))

  /** A path the program uses, and the variables holding what the scan found there. */
  private final class Node(
      val demand: Demand,
      val kind: Int,
      val number: Int, // the next three only when demand.asValue
      val start: Int,
      val len: Int,
      val children: Map[String, Node]
  ):
    def descendants: List[Node] = children.values.toList.flatMap(c => c :: c.descendants)

  private def node(d: Demand, slots: ArrayBuffer[SlotType]): Node =
    def slot(t: SlotType) = { slots += t; slots.size - 1 }
    val kind = slot(SlotType.Int)
    val (number, start, len) =
      if d.asValue then (slot(SlotType.Double), slot(SlotType.Int), slot(SlotType.Int)) else (-1, -1, -1)
    Node(d, kind, number, start, len, d.children.map((n, cd) => n -> node(cd, slots)))

  private def code(program: Jq)(using Quotes): Expr[Fn] =
    import Gen.*
    hoist(program) { consts =>
      val demand = Plan.demand(program, consts)
      val slots  = ArrayBuffer.empty[SlotType]
      val root   = node(demand, slots)

      '{ (record: Record[Any, Array[Byte]], out: ArrayBuffer[Record[Any, Array[Byte]]]) =>
        val bytes = record.value
        val cur   = JsonCursor(bytes, 0)
        ${
          declare(slots.toList.zipWithIndex) { vars =>
            val scan =
              if demand == Demand.empty then '{ cur.skipValue() } // nothing to read, but still validate
              else readValue(root, 'cur, vars, consts)
            val env = locally {
              given Ctx = Ctx(consts)
              values(root, Nil, vars, 'bytes)
            }
            given Ctx = Ctx(consts, pathMode = true, env = env)
            val input = env.getOrElse(Nil, SVal.PathRef(Nil))
            val run = gen(program, input, Some(Nil)) { v =>
              if v eq input then '{ out += record; () }
              else '{ out += record.copy(value = ${ materialize(v) }.noSpaces.getBytes(UTF_8)); () }
            }
            '{
              $scan
              cur.end()
              try $run
              catch case _: JqError => () // the record is skipped, outputs already emitted are kept
            }
          }
        }
      }
    }

  // ----------------------------------------------------------------------------- scanning

  private def readValue(n: Node, cur: Expr[JsonCursor], vars: Map[Int, Slot], consts: Gen.Constants)(using
      Quotes
  ): Expr[Unit] =
    def set(s: Int)(v: Expr[Any]): Expr[Unit] = vars(s)._2(v)
    def get[T: Type](s: Int): Expr[T]         = vars(s)._1.asExprOf[T]
    val asValue                               = n.demand.asValue

    val scalar: Expr[Unit] =
      if !asValue then '{ $cur.skipValue() }
      else
        '{
          val k = ${ get[Int](n.kind) }
          if k == JsonCursor.Number then
            ${ set(n.number)('{ $cur.readNumber() }) }
            ${ set(n.start)('{ $cur.valStart }) }
            ${ set(n.len)('{ $cur.valLen }) }
          else if k == JsonCursor.String then
            $cur.readString()
            ${ set(n.start)('{ $cur.valStart }) }
            ${ set(n.len)('{ $cur.valLen }) }
            if $cur.escaped then ${ set(n.kind)(Expr(JsonCursor.EscapedString)) }
          else
            val s = $cur.pos
            $cur.skipValue()
            ${ set(n.start)('s) }
            ${ set(n.len)('{ $cur.pos - s }) }
        }

    val body: Expr[Unit] =
      if n.children.isEmpty then scalar
      else
        val obj = '{
          // this key may have been seen before: what was found under it is forgotten
          ${ Expr.block(n.descendants.map(d => set(d.kind)(Expr(JsonCursor.Null))), '{ () }) }
          ${ if asValue then set(n.start)('{ $cur.pos }) else '{ () } }
          ${ readObject(n, cur, vars, consts) }
          ${ if asValue then set(n.len)('{ $cur.pos - ${ get[Int](n.start) } }) else '{ () } }
        }
        '{ if ${ get[Int](n.kind) } == JsonCursor.Object then $obj else $scalar }

    '{
      ${ set(n.kind)('{ $cur.kind() }) }
      $body
    }

  private def readObject(n: Node, cur: Expr[JsonCursor], vars: Map[Int, Slot], consts: Gen.Constants)(using
      Quotes
  ): Expr[Unit] =
    val dispatch = n.children.toList.foldRight('{ $cur.skipValue() }) { case ((name, child), otherwise) =>
      '{
        if $cur.keyIs(${ consts.utf8(name) }, ${ Expr(name) }) then ${ readValue(child, cur, vars, consts) }
        else $otherwise
      }
    }
    '{
      if $cur.beginObject() then
        var more = true
        while more do
          $cur.readKey()
          $dispatch
          more = $cur.nextMember()
    }

  // ---------------------------------------------------------------------- values found

  /** The value of every path the program uses, from the variables. */
  private def values(n: Node, path: Gen.Path, vars: Map[Int, Slot], bytes: Expr[Array[Byte]])(using
      Gen.Ctx,
      Quotes
  ): Map[Gen.Path, Gen.SVal] =
    import Gen.SVal.*
    def get[T: Type](s: Int): Expr[T] = vars(s)._1.asExprOf[T]
    val kind = get[Int](n.kind)

    val own =
      if !n.demand.asValue then Map.empty
      else
        val (start, len) = (get[Int](n.start), get[Int](n.len))
        val slice        = '{ JsonCursor.parse($bytes, $start, $len) }
        Map(
          path -> Choice(
            kind,
            Vector(
              Static(Json.Null),
              Static(Json.False),
              Static(Json.True),
              Number(get[Double](n.number), slice),
              Str(bytes, start, len),
              Text('{ JsonCursor.unescape($bytes, $start, $len) }),
              Opaque(slice, konsume.jq.Runtime.ArrayRank),
              Opaque(slice, konsume.jq.Runtime.ObjectRank)
            )
          )
        )

    // `.parent.name`: null under null (or a missing key), an error under anything but an object
    def under(v: Gen.SVal): Gen.SVal =
      Choice(
        kind,
        Vector.tabulate(8) {
          case JsonCursor.Null   => Static(Json.Null)
          case JsonCursor.Object => v
          case _                 => Fail("Cannot index a value that is not an object with a string")
        }
      )

    own ++ n.children.flatMap((name, c) => values(c, path :+ name, vars, bytes).map((p, v) => p -> under(v)))
