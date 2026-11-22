package buildyourown

/** A mini-jq program, as a tree.
  *
  * {{{
  * .user.age                    Field(Field(Identity, "user"), "age")
  * select(.age > 30) | .name    Pipe(Select(Compare(Gt, Field(Identity, "age"), Literal(Num(30)))),
  *                                   Field(Identity, "name"))
  * }}}
  */
enum Expr:
  /** `.`: the input itself */
  case Identity
  /** `.name` applied to the output of `target` */
  case Field(target: Expr, name: String)
  /** `30`, `"FR"` */
  case Literal(value: Value)
  /** `left == right`, `left > right`: a boolean */
  case Compare(op: Op, left: Expr, right: Expr)
  /** `select(cond)`: the input if `cond` is true, nothing otherwise */
  case Select(cond: Expr)
  /** `left | right`: `right` run on each output of `left` */
  case Pipe(left: Expr, right: Expr)

enum Op:
  case Eq, Gt
