package konsume.jq

import scala.quoted.*
import scala.quoted.staging.{Compiler, withQuotes}

/** One staging compiler for the whole JVM.
  *
  * Instantiating it is cheap, using it is not: the first compilation takes ~1s (the compiler
  * warms up), later ones take tens of milliseconds. Thread safe: `Compiler.run` is synchronized,
  * so compilations are serialized.
  */
private[konsume] object StagingCompiler:
  private given Compiler = Compiler.make(getClass.getClassLoader)

  def run[T](code: Quotes ?=> Expr[T]): T = staging.run(code)
  def show[T](code: Quotes ?=> Expr[T]): String = withQuotes(code.show)
