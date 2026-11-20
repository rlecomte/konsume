package konsume.jq

import scala.quoted.*
import scala.quoted.staging.{Compiler, withQuotes}

/** The staging compiler, for the whole JVM.
  *
  * Using it is not cheap: the first compilation takes ~1s (the compiler warms up), later ones
  * take tens of milliseconds. Thread safe: `Compiler.run` is synchronized, so compilations on one
  * instance are serialized.
  *
  * A `Compiler` keeps one compiler context for all its runs, and that context grows with each
  * of them (symbols, types, trees: ~24 KiB per compilation, never released even when the
  * compiled program is). So the instance is replaced every `recycleEvery` compilations: the old
  * context is then garbage, once the compilations still running on it are done.
  */
private[konsume] object StagingCompiler:
  /** Compilations per compiler instance. */
  val recycleEvery: Int = sys.props.get("konsume.staging.recycleEvery").fold(500)(_.toInt)

  private var current: Compiler = newCompiler()
  private var runs              = 0

  private def newCompiler(): Compiler = Compiler.make(getClass.getClassLoader)

  /** The compiler for the next compilation, replaced after `recycleEvery` of them. */
  private def next(): Compiler = synchronized {
    runs += 1
    if runs > recycleEvery then
      current = newCompiler()
      runs = 1
    current
  }

  def run[T](code: Quotes ?=> Expr[T]): T       = staging.run(code)(using next())
  def show[T](code: Quotes ?=> Expr[T]): String = withQuotes(code.show)(using next())
