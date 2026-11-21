package konsume.planned

import io.circe.Json

/** Where the value of a path is, once the payload is read. */
enum PVal:
  case PStatic(value: Json)
  case PLong(slot: Int)
  case PDouble(slot: Int, isFloat: Boolean)
  case PBoolean(slot: Int)
  case PString(off: Int, len: Int)
  /** a value still in the payload, at the position in slot `pos`: decoded by `value` when used,
    * its JSON view then kept in `refs(cache)` for the rest of the record (the reader empties it)
    */
  case PEncoded(pos: Int, cache: Int, rank: Int, value: Encoded)
  /** any JSON value, in `refs`: an element of a JSON value iterated on its JSON view */
  case PAny(slot: Int)
  case PEnum(slot: Int, symbols: Vector[String])
  case PChoice(index: Int, branches: Vector[PVal])
  /** `.name` or `[i]` of a value read entirely: done on its JSON view, when used */
  case PField(parent: PVal, step: Step)
  /** a JSON number: compared as a double, emitted as written */
  case PNumber(slot: Int, start: Int, len: Int)
  /** a JSON string with escapes: decoded when used */
  case PText(start: Int, len: Int)
  /** a JSON array or object, still in the payload: parsed when used */
  case PSlice(start: Int, len: Int, rank: Int)
  /** an array or a map whose number of elements was counted while reading (slot `count`), for
    * `length`; `whole` is the value itself, when it was read too
    */
  case PSized(count: Int, rank: Int, whole: Option[PVal])
  /** an error, raised if the value is used (`.a.b` when `.a` is a number) */
  case PFail(message: String)

/** How a reader decodes a value it left in the payload ([[PVal.PEncoded]]). */
trait Encoded:
  /** The JSON view of the value at `pos` in the frame's bytes. */
  def json(f: Frame, pos: Int): Json
  /** Writes the value at `pos` as JSON, the bytes `json(f, pos).noSpaces` would give. */
  def write(f: Frame, pos: Int, out: JsonOut): Unit
