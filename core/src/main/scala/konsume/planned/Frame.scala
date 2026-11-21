package konsume.planned

import konsume.Record

import scala.collection.mutable.ArrayBuffer

/** What the program needs while it runs on one record: the slots filled by the reader (longs,
  * doubles as raw bits, ints, booleans in `nums`, JSON in `refs`), the registers of the program
  * (values a node needs again after its operands ran), where to emit.
  */
class Frame(slots: Int, registers: Int):
  val nums                                        = new Array[Long](slots)
  val refs                                        = new Array[AnyRef](slots)
  val regs                                        = new Array[AnyRef](registers)
  var bytes: Array[Byte]                          = null
  var record: Record[Any, Array[Byte]]            = null
  var out: ArrayBuffer[Record[Any, Array[Byte]]] = null

  def begin(r: Record[Any, Array[Byte]], o: ArrayBuffer[Record[Any, Array[Byte]]]): Unit =
    record = r
    out = o
    bytes = r.value

  def clear(): Unit =
    record = null
    out = null
    bytes = null

  inline def double(s: Int): Double = java.lang.Double.longBitsToDouble(nums(s))
  inline def int(s: Int): Int       = nums(s).toInt
