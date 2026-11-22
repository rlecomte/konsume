package buildyourown

/** What the planned engine works on for one record: the slots the reading plan fills, and the
  * record's bytes. One per engine, reused from record to record: reading a record allocates
  * nothing.
  */
final class Frame(slots: Int):
  val nums: Array[Long]   = new Array[Long](slots)
  var bytes: Array[Byte] = null
