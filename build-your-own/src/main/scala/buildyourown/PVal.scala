package buildyourown

/** Where the value of a path is, once the record has been read by its plan. */
enum PVal:
  /** an int, in slot `slot` */
  case PInt(slot: Int)
  /** a string, still in the bytes: its offset in slot `off`, its length in slot `len` */
  case PStr(off: Int, len: Int)
  /** null: a field the schema does not have, or a field of something that is not a record */
  case PNull
