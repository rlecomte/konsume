package buildyourown

/** The type of a record: mini-Avro is not self-describing, the schema says how to read it. */
enum Schema:
  case SInt
  case SString
  case SRecord(fields: List[(String, Schema)])
