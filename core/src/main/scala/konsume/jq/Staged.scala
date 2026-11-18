package konsume.jq

import io.circe.Json
import konsume.Record

import scala.collection.mutable.ArrayBuffer
import scala.quoted.*
import scala.quoted.staging.{Compiler, withQuotes}

/** Compiles a jq program on JSON values to JVM bytecode at runtime, with multi-stage
  * programming: the program is only known at runtime, but then fixed for the life of the
  * consumer. See [[Gen]] for what the generated code looks like.
  */
object Staged:

  type Batch = Iterable[Record[Any, Json]] => Iterable[Record[Any, Json]]

  def compile(program: Jq): Batch = StagingCompiler.run(batch(program))

  /** The generated code, for debugging. */
  def show(program: Jq): String = StagingCompiler.show(batch(program))

  private def batch(program: Jq)(using Quotes): Expr[Batch] =
    import Gen.*
    hoist(program) { consts =>
      given Ctx = Ctx(consts)
      '{ (batch: Iterable[Record[Any, Json]]) =>
        val out = ArrayBuffer.empty[Record[Any, Json]]
        val it  = batch.iterator
        while it.hasNext do
          val record = it.next()
          val value  = record.value
          try
            ${
              gen(program, SVal.Dyn('value, isInput = true), None) {
                case SVal.Dyn(_, true) => '{ out += record; () }
                case v                 => '{ out += record.copy(value = ${ json(v) }); () }
              }
            }
          catch case _: JqError => () // the record is skipped, outputs already emitted are kept
        out
      }
    }

/** One staging compiler for the whole JVM.
  *
  * Instantiating it is cheap, using it is not: the first compilation takes ~1s (the compiler
  * warms up), later ones take tens of milliseconds. It is not thread safe.
  */
private[konsume] object StagingCompiler:
  private given Compiler = Compiler.make(getClass.getClassLoader)

  def run[T](code: Quotes ?=> Expr[T]): T = synchronized(staging.run(code))
  def show[T](code: Quotes ?=> Expr[T]): String = synchronized(withQuotes(code.show))
