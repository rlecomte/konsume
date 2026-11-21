package konsume

import io.circe.Json

/** The payload could not be read: malformed JSON, not Confluent-framed, unknown schema... */
final class DecodeError(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause, false, false)

/** Bytes of a record payload => the JSON view jq works on. */
trait Decoder:
  /** @throws DecodeError */
  def decode(bytes: Array[Byte]): Json
