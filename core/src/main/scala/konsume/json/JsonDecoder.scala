package konsume.json

import io.circe.Json
import konsume.{DecodeError, Decoder}

/** Reference JSON decoder (circe): the oracle of the planned engine. */
object JsonDecoder extends Decoder:
  def decode(bytes: Array[Byte]): Json =
    io.circe.jawn.parseByteArray(bytes).fold(e => throw DecodeError(e.message, e), identity)
