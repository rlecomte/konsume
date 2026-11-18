package konsume

/** A Kafka record, decoupled from the Kafka client so the processing part stays pure. */
final case class Record[+K, +V](
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long,
    key: K,
    value: V
)
