package konsume.avro

import konsume.DecodeError
import java.nio.ByteBuffer

/** Confluent wire format: magic byte 0, schema id (4 bytes, big endian), then the payload. */
object Confluent:
  final val MagicByte: Byte  = 0
  final val HeaderSize: Int  = 5

  /** @throws DecodeError */
  def schemaId(bytes: Array[Byte]): Int =
    if bytes == null || bytes.length < HeaderSize then throw DecodeError("too short for the Confluent wire format")
    if bytes(0) != MagicByte then throw DecodeError(s"unknown magic byte ${bytes(0)}")
    // big endian, without a ByteBuffer: called on every record
    (bytes(1) & 0xff) << 24 | (bytes(2) & 0xff) << 16 | (bytes(3) & 0xff) << 8 | (bytes(4) & 0xff)

  /** Schema ids used in a batch: what the consumer must fetch from the registry before
    * running the (pure) pipeline on it.
    */
  def schemaIds(payloads: Iterable[Array[Byte]]): Set[Int] =
    payloads.iterator.collect {
      case b if b != null && b.length >= HeaderSize && b(0) == MagicByte => ByteBuffer.wrap(b, 1, 4).getInt
    }.toSet

  def frame(schemaId: Int, payload: Array[Byte]): Array[Byte] =
    ByteBuffer.allocate(HeaderSize + payload.length).put(MagicByte).putInt(schemaId).put(payload).array()
