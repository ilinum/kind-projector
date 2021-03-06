package d_m

import scala.tools.nsc
import nsc.Global
import nsc.Phase
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import nsc.transform.Transform
import nsc.transform.InfoTransform
import nsc.transform.TypingTransformers
import nsc.symtab.Flags._
import nsc.ast.TreeDSL
import nsc.typechecker

import scala.reflect.NameTransformer

class KindProjector(val global: Global) extends Plugin {
  val name = "kind-projector"
  val description = "Expand type lambda syntax"
  val components = new KindRewriter(this, global) :: Nil
}

class KindRewriter(plugin: Plugin, val global: Global)
    extends PluginComponent with Transform with TypingTransformers with TreeDSL {

  import global._

  val sp = new StringParser[global.type](global)

  val runsAfter = "parser" :: Nil
  val phaseName = "kind-projector"

  lazy val genAsciiNames: Boolean =
    System.getProperty("kp:genAsciiNames") == "true"

  def newTransformer(unit: CompilationUnit) = new MyTransformer(unit)

  class MyTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {

    // reserve some names
    val TypeLambda1 = newTypeName("Lambda")
    val TypeLambda2 = newTypeName("λ")
    val Placeholder = newTypeName("$qmark")
    val CoPlaceholder = newTypeName("$plus$qmark")
    val ContraPlaceholder = newTypeName("$minus$qmark")

    // the name to use for the type lambda itself.
    // e.g. the L in ({ type L[x] = Either[x, Int] })#L.
    val LambdaName = newTypeName(if (genAsciiNames) "L_kp" else "Λ$")

    // these will be used for matching but aren't reserved
    val Plus = newTypeName("$plus")
    val Minus = newTypeName("$minus")

    /**
     * Produce type lambda param names.
     *
     * If genAsciiNames is set, the legacy names (X_kp0, X_kp1, etc)
     * will be used.
     *
     * Otherwise:
     *
     * The first parameter (i=0) will be α, the second β, and so on.
     * After producing ω (for i=24), the letters wrap back around with
     * a number appended, e.g. α1, β1, and so on.
     */
    def newParamName(i: Int): TypeName = {
      require(i >= 0)
      if (genAsciiNames) {
        newTypeName("X_kp%d" format i)
      } else {
        val j = i % 25
        val k = i / 25
        val c = ('α' + j).toChar
        val s = if (k == 0) s"$c" else s"$c$k"
        newTypeName(s)
      }
    }

    // Define some names (and bounds) that will come in handy.
    val NothingLower = gen.rootScalaDot(tpnme.Nothing)
    val AnyUpper = gen.rootScalaDot(tpnme.Any)
    val AnyRefBase = gen.rootScalaDot(tpnme.AnyRef)
    val DefaultBounds = TypeBoundsTree(NothingLower, AnyUpper)

    // Handy way to make a TypeName from a Name.
    def makeTypeName(name: Name): TypeName =
      newTypeName(name.toString)

    // We use this to create type parameters inside our type project, e.g.
    // the A in: ({type L[A] = (A, Int) => A})#L.
    def makeTypeParam(name: Name, bounds: TypeBoundsTree = DefaultBounds): TypeDef =
      TypeDef(Modifiers(PARAM), makeTypeName(name), Nil, bounds)

    // Like makeTypeParam but with covariance, e.g.
    // ({type L[+A] = ... })#L.
    def makeTypeParamCo(name: Name, bounds: TypeBoundsTree = DefaultBounds): TypeDef =
      TypeDef(Modifiers(PARAM | COVARIANT), makeTypeName(name), Nil, bounds)

    // Like makeTypeParam but with contravariance, e.g.
    // ({type L[-A] = ... })#L.
    def makeTypeParamContra(name: Name, bounds: TypeBoundsTree = DefaultBounds): TypeDef =
      TypeDef(Modifiers(PARAM | CONTRAVARIANT), makeTypeName(name), Nil, bounds)

    // The transform method -- this is where the magic happens.
    override def transform(tree: Tree): Tree = {

      // Given a name, e.g. A or `+A` or `A <: Foo`, build a type
      // parameter tree using the given name, bounds, variance, etc.
      def makeTypeParamFromName(name: Name) = {
        val decoded = NameTransformer.decode(name.toString)
        val src = s"type _X_[$decoded] = Unit"
        sp.parse(src) match {
          case Some(TypeDef(_, _, List(tpe), _)) => tpe
          case None => reporter.error(tree.pos, s"Can't parse param: $name"); null
        }
      }

      // Like makeTypeParam, but can be used recursively in the case of types
      // that are themselves parameterized.
      def makeComplexTypeParam(t: Tree): TypeDef = t match {
        case Ident(name) =>
          makeTypeParamFromName(name)

        case TypeDef(m, nm, ps, bs) =>
          TypeDef(Modifiers(PARAM), nm, ps.map(makeComplexTypeParam), bs)

        case ExistentialTypeTree(AppliedTypeTree(Ident(name), ps), _) =>
          val tparams = ps.map(makeComplexTypeParam)
          TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, DefaultBounds)

        case x =>
          reporter.error(x.pos, "Can't parse %s (%s)" format (x, x.getClass.getName))
          null.asInstanceOf[TypeDef]
      }

      // Given the list a::as, this method finds the last argument in the list
      // (the "subtree") and returns that separately from the other arguments.
      // The stack is just used to enable tail recursion, and a and as are
      // passed separately to avoid dealing with empty lists.
      def parseLambda(a: Tree, as: List[Tree], stack: List[Tree]): (List[Tree], Tree) =
        as match {
          case Nil => (stack.reverse, a)
          case h :: t => parseLambda(h, t, a :: stack)
        }

      // Builds the horrendous type projection tree. To remind the reader,
      // given List("A", "B") and <(A, Int, B)> we are generating a tree for
      // ({ type L[A, B] = (A, Int, B) })#L.
      def makeTypeProjection(innerTypes: List[TypeDef], subtree: Tree) =
        SelectFromTypeTree(
          CompoundTypeTree(
            Template(
              AnyRefBase :: Nil,
              ValDef(NoMods, nme.WILDCARD, TypeTree(), EmptyTree),
              TypeDef(
                NoMods,
                LambdaName,
                innerTypes,
                super.transform(subtree)) :: Nil)),
          LambdaName)

      // This method handles the explicit type lambda case, e.g.
      // Lambda[(A, B) => Function2[A, Int, B]] case.
      def handleLambda(a: Tree, as: List[Tree]) = {
        val (args, subtree) = parseLambda(a, as, Nil)
        val innerTypes = args.map {
          case Ident(name) =>
            makeTypeParamFromName(name)

          case AppliedTypeTree(Ident(Plus), Ident(name) :: Nil) =>
            makeTypeParamCo(name)

          case AppliedTypeTree(Ident(Minus), Ident(name) :: Nil) =>
            makeTypeParamContra(name)

          case AppliedTypeTree(Ident(name), ps) =>
            val tparams = ps.map(makeComplexTypeParam)
            TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, DefaultBounds)

          case ExistentialTypeTree(AppliedTypeTree(Ident(name), ps), _) =>
            val tparams = ps.map(makeComplexTypeParam)
            TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, DefaultBounds)

          case x =>
            reporter.error(x.pos, "Can't parse %s (%s)" format (x, x.getClass.getName))
            null.asInstanceOf[TypeDef]
        }
        makeTypeProjection(innerTypes, subtree)
      }

      // This method handles the implicit type lambda case, e.g.
      // Function2[?, Int, ?].
      def handlePlaceholders(t: Tree, as: List[Tree]) = {
        // create a new type argument list, catching placeholders and create
        // individual identifiers for them.
        val xyz = as.zipWithIndex.map {
          case (Ident(Placeholder), i) =>
            (Ident(newParamName(i)), Some(Right(Placeholder)))
          case (Ident(CoPlaceholder), i) =>
            (Ident(newParamName(i)), Some(Right(CoPlaceholder)))
          case (Ident(ContraPlaceholder), i) =>
            (Ident(newParamName(i)), Some(Right(ContraPlaceholder)))
          case (ExistentialTypeTree(AppliedTypeTree(Ident(Placeholder), ps), _), i) =>
            (Ident(newParamName(i)), Some(Left(ps.map(makeComplexTypeParam))))
          case (a, i) =>
            (super.transform(a), None)
        }

        // for each placeholder, create a type parameter
        val innerTypes = xyz.collect {
          case (Ident(name), Some(Right(Placeholder))) =>
            makeTypeParam(name)
          case (Ident(name), Some(Right(CoPlaceholder))) =>
            makeTypeParamCo(name)
          case (Ident(name), Some(Right(ContraPlaceholder))) =>
            makeTypeParamContra(name)
          case (Ident(name), Some(Left(tparams))) =>
            TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, DefaultBounds)
        }

        val args = xyz.map(_._1)

        // if we didn't have any placeholders use the normal transformation.
        // otherwise build a type projection.
        if (innerTypes.isEmpty) super.transform(tree)
        else makeTypeProjection(innerTypes, AppliedTypeTree(t, args))
      }

      tree match {
        // Lambda[A => Either[A, Int]] case.
        case AppliedTypeTree(Ident(TypeLambda1), AppliedTypeTree(_, a :: as) :: Nil) =>
          atPos(tree.pos.makeTransparent)(handleLambda(a, as))

        // λ[A => Either[A, Int]] case.
        case AppliedTypeTree(Ident(TypeLambda2), AppliedTypeTree(_, a :: as) :: Nil) =>
          atPos(tree.pos.makeTransparent)(handleLambda(a, as))

        // Either[?, Int] case (if no ? present this is a noop)
        case AppliedTypeTree(t, as) =>
          atPos(tree.pos.makeTransparent)(handlePlaceholders(t, as))

        // Otherwise, carry on as normal.
        case _ =>
          super.transform(tree)
      }
    }
  }
}
