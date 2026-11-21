package konsume.planned

import konsume.jq.Jq

/** What the shape of a program tells about its paths and outputs. */
private[planned] object Syntax:

  /** The static path of `p`'s output, given that of its input (`select` keeps it). */
  def pathOf(p: Jq, in: Option[Path]): Option[Path] = p match
    case Jq.Identity    => in
    case Jq.Select(_)   => in
    case Jq.Field(t, n) => pathOf(t, in).map(_ :+ Step.Key(n))
    case Jq.Index(t, i) if i >= 0 => pathOf(t, in).map(_ :+ Step.At(i))
    case Jq.Pipe(l, r)  => pathOf(r, pathOf(l, in))
    case _              => None

  /** The path of `p` if it is only made of field accesses: then it can be looked up instead of
    * evaluated (unlike `select(c).a`, whose `select` must run).
    */
  def staticPath(p: Jq, in: Option[Path]): Option[Path] = p match
    case Jq.Identity    => in
    case Jq.Field(t, n) => staticPath(t, in).map(_ :+ Step.Key(n))
    case Jq.Index(t, i) if i >= 0 => staticPath(t, in).map(_ :+ Step.At(i))
    case Jq.Pipe(l, r)  => staticPath(r, staticPath(l, in))
    case _              => None

  def singleOutput(p: Jq): Boolean = p match
    case Jq.Iterate(_) | Jq.Select(_) | Jq.Empty                       => false
    case Jq.Identity | Jq.Literal(_) | Jq.Not | Jq.Length              => true
    case Jq.Field(t, _)                                                 => singleOutput(t)
    case Jq.Index(t, _)                                                 => singleOutput(t)
    case Jq.Pipe(l, r)                                                  => singleOutput(l) && singleOutput(r)
    case Jq.Compare(_, l, r)                                            => singleOutput(l) && singleOutput(r)
    case Jq.And(l, r)                                                   => singleOutput(l) && singleOutput(r)
    case Jq.Or(l, r)                                                    => singleOutput(l) && singleOutput(r)
    case Jq.Test(x)                                                     => singleOutput(x)
    case Jq.StartsWith(x)                                               => singleOutput(x)
    case Jq.EndsWith(x)                                                 => singleOutput(x)
