package mlscript

import scala.util.chaining._
import scala.collection.mutable
import fastparse._, fastparse.ScalaWhitespace._
import mlscript.utils._, shorthands._
import mlscript.Lexer._

/** Parser for an ML-style input syntax, used in the legacy `ML*` tests. */
@SuppressWarnings(Array("org.wartremover.warts.All"))
class MLParser(origin: Origin, indent: Int = 0, recordLocations: Bool = true) {
  
  val keywords = Set(
    "def", "class", "trait", "type", "method", "mut",
    "let", "rec", "in", "fun", "with", "undefined", "null",
    "if", "then", "else", "match", "case", "of", "forall",
    "datatype", "match")
  def kw[p: P](s: String) = s ~~ !(letter | digit | "_" | "'")
  
  // NOTE: due to bug in fastparse, the parameter should be by-name!
  def locate[p:P, L <: Located](tree: => P[L]): P[L] = (Index ~~ tree ~~ Index).map {
    case (i0, n, i1) => n.withLoc(i0, i1, origin)
  }
  
  def toParam(t: Term): Tup =
    Tup((N, Fld(false, false, t)) :: Nil)
  
  def toParams(t: Term): Tup = t match {
    case t: Tup => t
    case _ => toParam(t)
  }
  def toParamsTy(t: Type): Tuple = t match {
    case t: Tuple => t
    case _ => Tuple((N, Field(None, t)) :: Nil)
  }
  
  def letter[p: P]     = P( lowercase | uppercase )
  def lowercase[p: P]  = P( CharIn("a-z") )
  def uppercase[p: P]  = P( CharIn("A-Z") )
  def digit[p: P]      = P( CharIn("0-9") )
  def number[p: P]: P[Int] = P( CharIn("0-9").repX(1).!.map(_.toInt) )
  def ident[p: P]: P[String] =
    P( (letter | "_") ~~ (letter | digit | "_" | "'").repX ).!.filter(!keywords(_))
  
  def termOrAssign[p: P]: P[Statement] = P( term ~ ("=" ~ term).? ).map {
    case (expr, N) => expr
    case (pat, S(bod)) => LetS(false, pat, bod)
  }

  def term[p: P]: P[Term] = P(let | fun | ite | forall | withsAsc | _match | adtMatchWith)
  
  def forall[p: P]: P[Term] = P( (kw("forall") ~/ tyVar.rep ~ "." ~ term).map {
    case (vars, ty) => Forall(vars.toList, ty)
  } )
  
  def lit[p: P]: P[Lit] =
    locate(number.map(x => IntLit(BigInt(x))) | Lexer.stringliteral.map(StrLit(_))
    | P(kw("undefined")).map(x => UnitLit(true)) | P(kw("null")).map(x => UnitLit(false)))
  
  def variable[p: P]: P[Var] = locate(ident.map(Var))

  def parenCell[p: P]: P[Either[Term, (Term, Boolean)]] = (("..." | kw("mut")).!.? ~ term).map {
    case (Some("..."), t) => Left(t)
    case (Some("mut"), t) => Right(t -> true)
    case (_, t) => Right(t -> false)
  }

  def parens[p: P]: P[Term] = locate(P( "(" ~/ parenCell.rep(0, ",") ~ ",".!.? ~ ")" ).map {
    case (Seq(Right(t -> false)), N) => Bra(false, t)
    case (Seq(Right(t -> true)), N) => Tup(N -> Fld(true, false, t) :: Nil) // ? single tuple with mutable
    case (ts, _) => 
      if (ts.forall(_.isRight)) Tup(ts.iterator.map {
        case R(f) => N -> Fld(f._2, false, f._1)
        case _ => die // left unreachable
      }.toList)
      else Splc(ts.map {
        case R((v, m)) => R(Fld(m, false, v))
        case L(spl) => L(spl)
      }.toList)
  })

  def subtermNoSel[p: P]: P[Term] = P( parens | record | lit | variable )
  
  def subterm[p: P]: P[Term] = P( Index ~~ subtermNoSel ~ (
    // Fields:
    ("." ~/ (variable | locate(("(" ~/ ident ~ "." ~ ident ~ ")")
      .map {case (prt, id) => Var(s"${prt}.${id}")})))
      .map {(t: Var) => Left(t)} |
    // Array subscripts:
    ("[" ~ term ~/ "]" ~~ Index).map {Right(_)}
    // Assignment:
    ).rep ~ "!".!.? ~ ("<-" ~ term).?).map {
      case (i0, st, sels, bang, a) =>
        val base0 = sels.foldLeft(st)((acc, t) => t match {
          case Left(se) => Sel(acc, se)
          case Right((su, i1)) => Subs(acc, su).withLoc(i0, i1, origin)
        })
        val base = if (bang.isEmpty) base0 else
          Inst(base0)
        a.fold(base)(Assign(base, _))
    }

  def record[p: P]: P[Rcd] = locate(P(
      "{" ~/ (kw("mut").!.? ~ variable ~ "=" ~ term map L.apply).|(kw("mut").!.? ~
        variable map R.apply).rep(sep = ";" | ",") ~ "}"
    ).map { fs => Rcd(fs.map{ 
        case L((mut, v, t)) => v -> Fld(mut.isDefined, false, t)
        case R(mut -> id) => id -> Fld(mut.isDefined, false, id) }.toList)})
  
  def fun[p: P]: P[Term] = locate(P( kw("fun") ~/ term ~ "->" ~ term ).map(nb => Lam(toParams(nb._1), nb._2)))
  
  def let[p: P]: P[Term] = locate(P(
      kw("let") ~/ kw("rec").!.?.map(_.isDefined) ~ variable ~ subterm.rep ~ "=" ~ term ~ kw("in") ~ term
    ) map {
      case (rec, id, ps, rhs, bod) => Let(rec, id, ps.foldRight(rhs)((i, acc) => Lam(toParams(i), acc)), bod)
    })
  
  def ite[p: P]: P[Term] = P( kw("if") ~/ term ~ kw("then") ~ term ~ kw("else") ~ term ).map(ite =>
    App(App(App(Var("if"), toParam(ite._1)), toParam(ite._2)), toParam(ite._3)))
  
  def withsAsc[p: P]: P[Term] = P( withs ~ (":" ~/ ty).rep ).map {
    case (withs, ascs) => ascs.foldLeft(withs)(Asc)
  }
  
  def withs[p: P]: P[Term] = P( binops ~ (kw("with") ~ record).rep ).map {
    case (as, ws) => ws.foldLeft(as)((acc, w) => With(acc, w))
  }
  
  def mkApp(lhs: Term, rhs: Term): Term = App(lhs, toParams(rhs))
  def apps[p: P]: P[Term] = P( subterm.rep(1).map(_.reduce(mkApp)) )
  
  def _match[p: P]: P[CaseOf] =
    locate(P( kw("case") ~/ term ~ "of" ~ ("{" ~ "|".? ~ matchArms("|") ~ "}" | matchArms(",")) ).map(CaseOf.tupled))
  def matchArms[p: P](sep: Str): P[CaseBranches] = P(
    ( ("_" ~ "->" ~ term).map(Wildcard)
    | ((lit | variable) ~ "->" ~ term ~ matchArms2(sep))
      .map { case (t, b, rest) => Case(t, b, rest) }
    ).?.map {
      case None => NoCases
      case Some(b) => b
    }
  )
  def matchArms2[p: P](sep: Str): P[CaseBranches] = (sep ~ matchArms(sep)).?.map(_.getOrElse(NoCases))
  
  private val prec: Map[Char,Int] = List(
    ":",
    "|",
    "^",
    "&",
    "= !",
    "< >",
    "+ -",
    "* / %",
    ".",
  ).zipWithIndex.flatMap {
    case (cs,i) => cs.filterNot(_ == ' ').map(_ -> i)
  }.toMap.withDefaultValue(Int.MaxValue)
  def precedence(op: String): Int = prec(op.head) min prec(op.last)
  
  // Adapted from: https://github.com/databricks/sjsonnet/blob/master/sjsonnet/src/sjsonnet/Parser.scala#L136-L180
  def binops[p: P]: P[Term] =
    P(apps ~ (Index ~~ operator.! ~~ Index ~/ apps).rep ~ "").map { case (pre, fs) =>
      var remaining = fs
      def climb(minPrec: Int, current: Term): Term = {
        var result = current
        while (
          remaining.headOption match {
            case None => false
            case Some((off0, op, off1, next)) =>
              val prec: Int = precedence(op)
              if (prec < minPrec) false
              else {
                remaining = remaining.tail
                val rhs = climb(prec + 1, next)
                result = App(App(Var(op).withLoc(off0, off1, origin), toParams(result)), toParams(rhs))
                true
              }
          }
        )()
        result
      }
      climb(0, pre)
    }
  def operator[p: P]: P[Unit] = P(
    !symbolicKeywords ~~ (!StringIn("/*", "//") ~~ (CharsWhile(OpCharNotSlash) | "/")).rep(1)
  ).opaque("operator")
  def symbolicKeywords[p: P] = P{
    StringIn(
      "|",
      "~",
      ";", "=>", "=", "->", "<-",
      ":",
      "#", "@", "\\", "\u21d2", "\u2190"
    ) ~~ !OpChar
  }.opaque("symbolic keywords")
  
  def expr[p: P]: P[Term] = P( term ~ End )
  
  def defDecl[p: P]: P[Def] =
    locate(P((kw("def") ~ variable ~ tyParams ~ ":" ~/ ty map {
      case (id, tps, t) => Def(true, id, R(PolyType(tps.map(L(_)), t)), true)
    }) | (kw("rec").!.?.map(_.isDefined) ~ kw("def") ~/ variable ~ subterm.rep ~ "=" ~ term map {
      case (rec, id, ps, bod) => Def(rec, id, L(ps.foldRight(bod)((i, acc) => Lam(toParams(i), acc))), true)
    })))
  
  def tyKind[p: P]: P[TypeDefKind] = (kw("class") | kw("trait") | kw("type")).! map {
    case "class" => Cls
    case "trait" => Trt
    case "type"  => Als
  }
  def tyDecl[p: P]: P[TypeDef] =
    P((tyKind ~/ tyName ~ tyParams).flatMap {
      case (k @ (Cls | Trt), id, ts) => (":" ~ ty).? ~ (mthDecl(id) | mthDef(id)).rep.map(_.toList) map {
        case (bod, ms) => TypeDef(k, id, ts, bod.getOrElse(Top), 
          ms.collect { case R(md) => md }, ms.collect{ case L(md) => md }, Nil)
      }
      case (k @ Als, id, ts) => "=" ~ ty map (bod => TypeDef(k, id, ts, bod, Nil, Nil, Nil))
      case (k @ Nms, _, _) => throw new NotImplementedError("Namespaces are not supported yet.")
    })
  def tyParams[p: P]: P[Ls[TypeName]] =
    ("[" ~ tyName.rep(0, ",") ~ "]").?.map(_.toList.flatten)
  def mthDecl[p: P](prt: TypeName): P[R[MethodDef[Right[Term, Type]]]] = 
    P(kw("method") ~ variable ~ tyParams ~ ":" ~/ ty map {
      case (id, ts, t) => R(MethodDef[Right[Term, Type]](true, prt, id, ts, R(t)))
    })
  def mthDef[p: P](prt: TypeName): P[L[MethodDef[Left[Term, Type]]]] = 
    P(kw("rec").!.?.map(_.isDefined) ~ kw("method") ~ variable ~ tyParams ~ subterm.rep ~ "=" ~/ term map {
      case (rec, id, ts, ps, bod) =>
        L(MethodDef(rec, prt, id, ts, L(ps.foldRight(bod)((i, acc) => Lam(toParams(i), acc)))))
    })
  
  def ty[p: P]: P[Type] = P( tyNoRange ~ (".." ~ tyNoRange).? ).map {
    case (res, N) => res
    case (lb, S(ub)) => Bounds(lb, ub)
  }
  def tyNoRange[p: P]: P[Type] = P( tyNoAs ~ ("as" ~ tyVar).rep ).map {
    case (ty, ass) => ass.foldLeft(ty)((a, b) => Recursive(b, a))
  }
  def tyNoAs[p: P]: P[Type] = P( (kw("forall") ~/ tyVar.rep ~ "." ~ tyNoAs).map {
    case (vars, ty) => PolyType(vars.map(R(_)).toList, ty)
  } | tyNoForall )
  def tyNoForall[p: P]: P[Type] = P( tyNoUnion.rep(1, "|") ).map(_.reduce(Union) )
  def tyNoUnion[p: P]: P[Type] = P( tyNoInter.rep(1, "&") ).map(_.reduce(Inter) )
  def tyNoInter[p: P]: P[Type] = P( tyNoFun ~ ("->" ~/ tyNoInter).? ).map {
    case (l, S(r)) => Function(toParamsTy(l), r)
    case (l, N) => l
  }
  // Note: field removal types are not supposed to be explicitly used by programmers,
  //    and they won't work in negative positions,
  //    but parsing them is useful in tests (such as shared/src/test/diff/mlscript/Annoying.mls)
  def tyNoFun[p: P]: P[Type] = P( (rcd | ctor | parTy) ~ ("\\" ~ variable).rep(0) ) map {
    case (ty, Nil) => ty
    case (ty, ids) => Rem(ty, ids.toList)
  }
  def ctor[p: P]: P[Type] = locate(P( tyName ~ "[" ~ ty.rep(0, ",") ~ "]" ) map {
    case (tname, targs) => AppliedType(tname, targs.toList)
  }) | tyNeg | tyName | tyTag | tyVar | tyWild | litTy
  def tyNeg[p: P]: P[Type] = locate(P("~" ~/ tyNoFun map { t => Neg(t) }))
  def tyTag[p: P]: P[TypeTag] = locate(P("#" ~~ (ident map TypeTag)))
  def tyName[p: P]: P[TypeName] = locate(P(ident map TypeName))
  def tyVar[p: P]: P[TypeVar] = locate(P(
    (("α" | "β" | "γ" | "δ" | "'").! ~ ident.? map {
      case (pre, id) => TypeVar(R(pre + id.getOrElse("")), N)
    })
  ))
  def tyWild[p: P]: P[Bounds] = locate(P("?".! map (_ => Bounds(Bot, Top))))
  def rcd[p: P]: P[Record] =
    locate(P( "{" ~/ ( kw("mut").!.? ~ variable ~ ":" ~ ty).rep(sep = ";") ~ "}" )
      .map(_.toList.map {
        case (None, v, t) => v -> Field(None, t)
        case (Some(_), v, t) => v -> Field(Some(t), t)
      } pipe Record))

  def parTyCell[p: P]: P[Either[Type, (Type, Boolean)]] = (("..." | kw("mut")).!.? ~ ty). map {
    case (Some("..."), t) => Left(t)
    case (Some("mut"), t) => Right(t -> true)
    case (_, t) => Right(t -> false)
  }

  def parTy[p: P]: P[Type] = locate(P( "(" ~/ parTyCell.rep(0, ",").map(_.map(N -> _).toList) ~ ",".!.? ~ ")" ).map {
    case (N -> Right(ty -> false) :: Nil, N) => ty
    case (fs, _) => 
      if (fs.forall(_._2.isRight))
        Tuple(fs.map {
          case (l, Right(t -> false)) => l -> Field(None, t)
          case (l, Right(t -> true)) => l -> Field(Some(t), t)
          case _ => ??? // unreachable
        })
      else Splice(fs.map{ _._2 match { 
        case L(l) => L(l) 
        case R(r -> true) => R(Field(Some(r), r))
        case R(r -> false) => R(Field(None, r))
      } })
  })
  def litTy[p: P]: P[Type] = P( lit.map(l => Literal(l).withLocOf(l)) )
  
  def toplvl[p: P]: P[Ls[Statement]] = P(adtTyDecl) | P( defDecl | tyDecl | termOrAssign ).map(_ :: Nil)
  def pgrm[p: P]: P[Pgrm] = P( (";".rep ~ toplvl ~ topLevelSep.rep).rep.map(_.toList.flatten) ~ End ).map(Pgrm)
  def topLevelSep[p: P]: P[Unit] = ";"
  
  private var curHash = 0
  def nextHash: Int = {
    val res = curHash
    curHash = res + 1
    res
  }

  ///////////////////////////////////////////////////////
  /// ADT types
  ///////////////////////////////////////////////////////

  def ctorName[p: P]: P[TypeName] =
    locate(P(uppercase ~~ (letter | digit | "_" | "'").repX).!.filter(!keywords(_)).map(TypeName))

  /** data constructor declaration
    * type 'a, 'b tup = [Tup of 'a * 'b]
    */
  def adtCtorDecl[p: P]: P[TypeDef] =
    P((ctorName ~ tyParams ~ parTy.?).map {
      case (id, params, S(body: Tuple)) =>
        val positionals = body.fields.zipWithIndex.map { case (_, i) => Var("_"+(i+1)) }
        TypeDef(Cls, id, params, body, Nil, Nil, positionals)
      case (id, _, None) => TypeDef(Cls, id, Nil, Tuple(Ls.empty), Nil, Nil, Nil)
      case t => throw new Exception(s"Unable to handle case $t")
    })

  def adtDataCtor[p: P]: P[List[TypeDef]] = adtCtorDecl.rep(1, "||").map(_.toList)

  def adtTyDecl[p: P]: P[Ls[Statement]] =
    P((kw("datatype") ~ ctorName ~ tyParams ~ "=" ~/ adtDataCtor).map {
      // parsed data constructors create classes and helper functions
      // create an alias for the type itself
      case (alsName, tparams, bodies) =>
        val paramSet = tparams.toSet
        bodies.foreach(tyDef => {
          val paramMapIndex = tyDef.tparams.filter(paramSet(_)).map(elem => tparams.zipWithIndex.filter(_._1 == elem).head._2)
          tyDef.adtInfo = AdtInfo(alsName, paramMapIndex) :: Nil
        })
        val aliasBody = bodies.foldLeft[Type](bodies.head.body) {
          case (union, tdef) => Union(tdef.body, union)
        }
        val constructors = bodies.flatMap(cls => adtTyConstructors(cls, alsName, tparams))
        val als = TypeDef(Als, alsName, tparams, aliasBody, Nil, Nil, Nil)
        als.adtInfo = bodies.flatMap(tdef => tdef.adtInfo.headOption.map(_.copy(ctorName = tdef.nme)))
        // Don't add type definitions for the bodies
        // only use their constructors
        als :: constructors
    })

  // create a helper function for a class constructor
  //
  // Data constructor helper functions need to be typed as returning the alias
  // type 'a, 'b either = left of 'a | right of 'b
  // will create
  // * alias either['a, 'b]
  // * class left['a']
  // * class right['b']
  // * constructor left(a): either['a, 'b]
  // * constructor right(b): either['a, 'b]
  def adtTyConstructors(tyDef: TypeDef, alsName: TypeName, alsParams: Ls[TypeName]): Opt[Def] = {
    val alsTy = (alsParams match {
      case Nil => alsName
      case params => AppliedType(alsName, params)
    }).withLocOf(tyDef)
    tyDef.kind match {
      case Cls =>
        tyDef.body match {
          case Tuple(Nil) =>
            val funAppTy = PolyType(alsParams.map(L.apply), alsTy)
            val fun = Def(false, Var(tyDef.nme.name), R(funAppTy), true).withLocOf(tyDef.nme)
            S(fun)
          case tup: Tuple =>
            val funTy = PolyType(alsParams.map(L.apply), Function(tup, alsTy))
            val fun = Def(false, Var(tyDef.nme.name), R(funTy), true).withLocOf(tyDef.nme)
            S(fun)
          case _ => N
        }
      case _ => N
    }
  }

  ///////////////////////////////////////////////////////
  /// ADT match with
  ///////////////////////////////////////////////////////

  def adtMatchWith[p: P]: P[AdtMatchWith] =
    locate(P(kw("match") ~/ term ~ "with" ~ "|".? ~ matchArms).map {
      case (expr, arms) => AdtMatchWith(expr, arms)
    })

  def matchArms[p: P]: P[Ls[AdtMatchArm]] = P(
    (
      ("_" ~ "->" ~ term).map(AdtMatchElse(_) :: Nil)
        // In ocaml Tup _ -> desugars to Tup(_, _) -> i.e. matching the constructor
        // but ignoring the values. In UCS this is represented by matching on
        // just the data constructor Tup ->
        | (variable ~ "_" ~ "->" ~ term ~ matchArms2).map {
        case (t, b, rest) =>
          AdtMatchPat(t, b) :: rest
      }
        | (term ~ "->" ~ term ~ matchArms2).map {
        case (t, b, rest) =>
          AdtMatchPat(t, b) :: rest
      }
      ).?.map {
      case None => Ls.empty
      case Some(b) => b
    })

  def matchArms2[p: P]: P[Ls[AdtMatchArm]] = ("|" ~ matchArms).?.map(_.getOrElse(Ls.empty))
}
object MLParser {
  
  def addTopLevelSeparators(lines: IndexedSeq[Str]): IndexedSeq[Str] = {
    (lines.iterator ++ lines.lastOption).toList.sliding(2).map {
      case l0 :: l1 :: Nil =>
        if (l1.startsWith(" ") || l1.startsWith("\t")) l0 + "\n"
        else l0 + ";"
      case l :: Nil => l
      case _ => die
    }.toIndexedSeq
  }
  
}
