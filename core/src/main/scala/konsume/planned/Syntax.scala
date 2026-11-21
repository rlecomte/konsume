package konsume.planned

import konsume.jq.Jq

/** What the shape of a program tells about its paths and outputs. */
private[planned] object Syntax:

  /** The static path of `p`'s outputs, given that of its input (`select` keeps it). `.a[]` is
    * the path `.a[]` (each element in turn) when `each(.a)`: the array is walked element by
    * element, through slots; otherwise its outputs have no path.
    */
  def pathOf(p: Jq, in: Option[Path], each: Path => Boolean): Option[Path] = p match
    case Jq.Identity    => in
    case Jq.Select(_)   => in
    case Jq.Field(t, n) => pathOf(t, in, each).map(_ :+ Step.Key(n))
    case Jq.Index(t, i) if i >= 0 => pathOf(t, in, each).map(_ :+ Step.At(i))
    case Jq.Iterate(t)  => pathOf(t, in, each).filter(each).map(_ :+ Step.Each)
    case Jq.Pipe(l, r)  => pathOf(r, pathOf(l, in, each), each)
    case _              => None

  /** The paths of the arrays `program` iterates at one place only. An array iterated at two
    * places (`.a[] > .a[]`) has its two walks nested: the inner one would overwrite the element
    * the outer one is using, both being in the same slots.
    */
  def iteratedOnce(program: Jq): Set[Path] =
    val all: Path => Boolean = _ => true
    def iterated(p: Jq, in: Option[Path]): List[Path] = p match
      case Jq.Iterate(t)       => iterated(t, in) ++ pathOf(t, in, all)
      case Jq.Pipe(l, r)       => iterated(l, in) ++ iterated(r, pathOf(l, in, all))
      case Jq.Field(t, _)      => iterated(t, in)
      case Jq.Index(t, _)      => iterated(t, in)
      case Jq.Compare(_, l, r) => iterated(l, in) ++ iterated(r, in)
      case Jq.And(l, r)        => iterated(l, in) ++ iterated(r, in)
      case Jq.Or(l, r)         => iterated(l, in) ++ iterated(r, in)
      case Jq.Select(c)        => iterated(c, in)
      case Jq.Test(x)          => iterated(x, in)
      case Jq.StartsWith(x)    => iterated(x, in)
      case Jq.EndsWith(x)      => iterated(x, in)
      case Jq.Identity | Jq.Literal(_) | Jq.Empty | Jq.Not | Jq.Length => Nil
    iterated(program, Some(Nil)).groupBy(identity).collect { case (a, uses) if uses.size == 1 => a }.toSet

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
