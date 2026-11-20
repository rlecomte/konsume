package konsume

import io.circe.Json
import konsume.format.{AvroDecoder, AvroPlanned, Confluent, DecodeError, Decoder, JsonDecoder}
import konsume.jq.{Interpreter, Jq, JqError, ParseError, Parser}
import org.apache.avro.Schema

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer

/** How the jq program is executed. */
enum Engine:
  /** Avro: only the fields the program uses are read from the bytes, the program runs as
    * closures specialised on their types, see [[format.AvroPlanned]]. JSON: not done yet, the
    * same as `Interpreted`.
    */
  case Planned
  /** Values decoded to JSON first, then a tree-walking interpreter: the reference semantics. */
  case Interpreted

/** How record values are encoded. */
enum PayloadFormat:
  case Json
  /** Confluent wire format, writer schemas from a schema registry. */
  case Avro

final case class ConsumeConfig(
    topics: Seq[String],
    valueFormat: PayloadFormat = PayloadFormat.Json,
    /** A jq program applied to each record value; one output record per jq output. */
    jq: Option[String] = None,
    engine: Engine = Engine.Planned
)

/** The pure part of a consumer: a batch of polled records in, the records to emit out. */
trait Pipeline[-K, -V, +K2, +V2]:
  def apply(batch: Iterable[Record[K, V]]): Iterable[Record[K2, V2]]

object Pipeline:

  def identity[K, V]: Pipeline[K, V, K, V] = batch => batch

  /** The pipeline on raw Kafka values.
    *
    * Values are seen through their JSON view by the jq program. A record the program keeps
    * unchanged (a filter) is emitted with its original bytes, untouched; a transformed value is
    * emitted as JSON. Records that cannot be decoded are skipped.
    *
    * With the planned engine, Avro bytes are read directly, see [[format.AvroPlanned]]. Otherwise
    * values are decoded to JSON first (circe, or Apache Avro then converted), then interpreted.
    *
    * @param schemas
    *   Avro writer schemas by registry id, fetched beforehand by the consumer.
    */
  def forBytes[K](
      config: ConsumeConfig,
      schemas: Int => Option[Schema] = _ => None
  ): Either[ParseError, Pipeline[K, Array[Byte], K, Array[Byte]]] =
    config.jq match
      case None => Right(identity)
      case Some(source) =>
        Parser.parse(source).map { program =>
          (config.valueFormat, config.engine) match
            case (PayloadFormat.Avro, Engine.Planned) => avroPlanned(program, schemas)
            case (format, _) =>
              val decoder: Decoder = format match
                case PayloadFormat.Json => JsonDecoder
                case PayloadFormat.Avro => AvroDecoder(schemas)
              decodeThen(decoder, interpreted(program))
        }

  /** Decodes each value to JSON, then runs `jq` on it. */
  def decodeThen[K](decoder: Decoder, jq: Pipeline[K, Json, K, Json]): Pipeline[K, Array[Byte], K, Array[Byte]] =
    batch =>
      // Record by record: the decoded value is filtered while it is still in the CPU cache,
      // and is garbage as soon as the record is done (decoding the whole batch first and
      // then filtering it was twice as slow).
      val out = ArrayBuffer.empty[Record[K, Array[Byte]]]
      batch.foreach { r =>
        val decoded =
          try r.copy(value = decoder.decode(r.value))
          catch case _: DecodeError => null
        if decoded != null then
          jq(decoded :: Nil).foreach { o =>
            out += (if o eq decoded then r else o.copy(value = o.value.noSpaces.getBytes(UTF_8)))
          }
      }
      out

  /** One program per writer schema id, built on first sight of the id. */
  private def avroPlanned[K](program: Jq, schemas: Int => Option[Schema]): Pipeline[K, Array[Byte], K, Array[Byte]] =
    val bySchemaId = ConcurrentHashMap[Int, AvroPlanned.Fn]()
    batch =>
      val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
      batch.foreach { r =>
        try
          val id = Confluent.schemaId(r.value)
          var f  = bySchemaId.get(id)
          if f == null then
            val schema = schemas(id).getOrElse(throw DecodeError(s"unknown schema id $id"))
            f = AvroPlanned.compile(program, schema)
            bySchemaId.put(id, f)
          f(r, out)
        catch case _: DecodeError | _: IndexOutOfBoundsException => () // undecodable: skipped
      }
      // AvroPlanned only knows Record[Any, _]: it copies keys, never looks at them.
      out.asInstanceOf[ArrayBuffer[Record[K, Array[Byte]]]]

  def interpreted[K](program: Jq): Pipeline[K, Json, K, Json] = batch =>
    val out = ArrayBuffer.empty[Record[K, Json]]
    batch.foreach { record =>
      try Interpreter.eval(program, record.value)(v => out += (if v eq record.value then record else record.copy(value = v)))
      catch case _: JqError => ()
    }
    out
