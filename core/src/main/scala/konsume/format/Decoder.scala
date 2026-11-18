package konsume.format

import io.circe.Json

/** The payload could not be read: malformed JSON, not Confluent-framed, unknown schema... */
final class DecodeError(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause, false, false)

/** Bytes of a record payload => the JSON view jq works on. */
trait Decoder:
  /** @throws DecodeError */
  def decode(bytes: Array[Byte]): Json

object JsonDecoder extends Decoder:
  def decode(bytes: Array[Byte]): Json =
    io.circe.jawn.parseByteArray(bytes).fold(e => throw DecodeError(e.message, e), identity)
