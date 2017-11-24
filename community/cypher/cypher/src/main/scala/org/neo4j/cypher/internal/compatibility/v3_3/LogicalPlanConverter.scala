package org.neo4j.cypher.internal.compatibility.v3_3

import java.lang.reflect.Constructor

import org.neo4j.cypher.internal.frontend.v3_3.ast.{Expression => ExpressionV3_3}
import org.neo4j.cypher.internal.frontend.v3_3.{SemanticDirection => SemanticDirectionV3_3, ast => astV3_3, symbols => symbolsV3_3}
import org.neo4j.cypher.internal.frontend.{v3_3 => frontendV3_3}
import org.neo4j.cypher.internal.ir.v3_3.{IdName => IdNameV3_3}
import org.neo4j.cypher.internal.ir.v3_4.{PlannerQuery, IdName => IdNameV3_4}
import org.neo4j.cypher.internal.util.v3_4.Rewritable.{DuplicatableProduct, RewritableAny}
import org.neo4j.cypher.internal.util.v3_4.{symbols => symbolsV3_4, _}
import org.neo4j.cypher.internal.v3_3.logical.plans.{LogicalPlan => LogicalPlanV3_3}
import org.neo4j.cypher.internal.v3_3.logical.{plans => plansV3_3}
import org.neo4j.cypher.internal.v3_4.expressions.{Expression => ExpressionV3_4}
import org.neo4j.cypher.internal.v3_4.logical.plans.{LogicalPlan => LogicalPlanV3_4}
import org.neo4j.cypher.internal.v3_4.{expressions => expressionsV3_4}

import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util.{Failure, Success, Try}

object LogicalPlanConverter {
  private val rewriter: RewriterWithArgs = bottomUpWithArgs(RewriterWithArgs.lift {
    case (plan: plansV3_3.LogicalPlan, children: Seq[AnyRef]) =>
      val newPlan = convertVersion("v3_3", "v3_4", plan, children, null, classOf[PlannerQuery])
      newPlan.asInstanceOf[LogicalPlanV3_4].setIdTo(helpers.as3_4(plan.assignedId))
      newPlan

    case (inp: astV3_3.InvalidNodePattern, children: Seq[AnyRef]) =>
      new expressionsV3_4.InvalidNodePattern(children.head.asInstanceOf[Option[expressionsV3_4.Variable]].get)(helpers.as3_4(inp.position))
    case (mp: astV3_3.MapProjection, children: Seq[AnyRef]) =>
      expressionsV3_4.MapProjection(children(0).asInstanceOf[expressionsV3_4.Variable],
        children(1).asInstanceOf[Seq[expressionsV3_4.MapProjectionElement]])(helpers.as3_4(mp.position))

    case (expressionV3_3: astV3_3.rewriters.DesugaredMapProjection, children: Seq[AnyRef]) =>
      convertVersion("v3_3", "v3_4", expressionV3_3, children, helpers.as3_4(expressionV3_3.position), classOf[InputPosition])
    case (expressionV3_3: astV3_3.ASTNode, children: Seq[AnyRef]) =>
      convertVersion("frontend.v3_3.ast", "v3_4.expressions", expressionV3_3, children, helpers.as3_4(expressionV3_3.position), classOf[InputPosition])
    case (IdNameV3_3(name), _) => IdNameV3_4(name)

    case (symbolsV3_3.CTAny, _) => symbolsV3_4.CTAny
    case (symbolsV3_3.CTBoolean, _) => symbolsV3_4.CTBoolean
    case (symbolsV3_3.CTFloat, _) => symbolsV3_4.CTFloat
    case (symbolsV3_3.CTGeometry, _) => symbolsV3_4.CTGeometry
    case (symbolsV3_3.CTGraphRef, _) => symbolsV3_4.CTGraphRef
    case (symbolsV3_3.CTInteger, _) => symbolsV3_4.CTInteger
    case (symbolsV3_3.ListType(_), children: Seq[AnyRef]) => symbolsV3_4.CTList(children.head.asInstanceOf[symbolsV3_4.CypherType])
    case (symbolsV3_3.CTMap, _) => symbolsV3_4.CTMap
    case (symbolsV3_3.CTNode, _) => symbolsV3_4.CTNode
    case (symbolsV3_3.CTNumber, _) => symbolsV3_4.CTNumber
    case (symbolsV3_3.CTPath, _) => symbolsV3_4.CTPath
    case (symbolsV3_3.CTPoint, _) => symbolsV3_4.CTPoint
    case (symbolsV3_3.CTRelationship, _) => symbolsV3_4.CTRelationship
    case (symbolsV3_3.CTString, _) => symbolsV3_4.CTString

    case (SemanticDirectionV3_3.BOTH, _) => expressionsV3_4.SemanticDirection.BOTH
    case (SemanticDirectionV3_3.INCOMING, _) => expressionsV3_4.SemanticDirection.INCOMING
    case (SemanticDirectionV3_3.OUTGOING, _) => expressionsV3_4.SemanticDirection.OUTGOING

    case (astV3_3.NilPathStep, _) => expressionsV3_4.NilPathStep
    case (pathStep: astV3_3.PathStep, children: Seq[AnyRef]) =>
      convertVersion("frontend.v3_3.ast", "v3_4.expressions", pathStep, children)
    case (_: frontendV3_3.helpers.NonEmptyList[_], children: Seq[AnyRef]) => NonEmptyList.from(children)

    case (_: List[_], children: Seq[AnyRef]) => children.toList
    case (_: Seq[_], children: Seq[AnyRef]) => children.toIndexedSeq
    case (_: Set[_], children: Seq[AnyRef]) => children.toSet
    case (_: Map[_, _], children: Seq[AnyRef]) => Map(children.map(_.asInstanceOf[(_, _)]): _*)
    case (None, _) => None
    case (p: Product, children: Seq[AnyRef]) => new DuplicatableProduct(p).copyConstructor.invoke(p, children: _*)
  })

  def convertLogicalPlan[T <: LogicalPlanV3_4](logicalPlan: LogicalPlanV3_3): LogicalPlanV3_4 = {
    new RewritableAny[LogicalPlanV3_3](logicalPlan).rewrite(rewriter, Seq.empty).asInstanceOf[T]
  }

  def convertExpression[T <: ExpressionV3_4](expression: ExpressionV3_3): T = {
    new RewritableAny[ExpressionV3_3](expression).rewrite(rewriter, Seq.empty).asInstanceOf[T]
  }

  private val constructors = new ThreadLocal[MutableHashMap[(String, String, String), Constructor[_]]]() {
    override def initialValue: MutableHashMap[(String, String, String), Constructor[_]] =
      new MutableHashMap[(String, String, String), Constructor[_]]
  }

  private def getConstructor(classNameV3_3: String, oldPackage: String, newPackage: String): Constructor[_] = {
    constructors.get.getOrElseUpdate((classNameV3_3, oldPackage, newPackage), {
      val classNameV3_4 = classNameV3_3.replace(oldPackage, newPackage)
      Try(Class.forName(classNameV3_4)).map(_.getConstructors.head) match {
        case Success(c) => c
        case Failure(e: ClassNotFoundException) => throw new InternalException(
          s"Failed trying to rewrite $classNameV3_3 - 3.4 class not found ($classNameV3_4)", e)
        case Failure(e: NoSuchElementException) => throw new InternalException(
          s"Failed trying to rewrite $classNameV3_3 - this class does not have a constructor", e)
      }
    })
  }

  private def convertVersion(oldPackage: String, newPackage: String, thing: AnyRef, children: Seq[AnyRef], extraArg: AnyRef = null, assignableClazzForArg: Class[_] = null): AnyRef = {
    val thingClass = thing.getClass
    val classNameV3_3 = thingClass.getName
    val constructor = getConstructor(classNameV3_3, oldPackage, newPackage)

    val params = constructor.getParameterTypes
    val args = children.toVector
    val ctorArgs = if (params.length == args.length + 1 && params.last.isAssignableFrom(assignableClazzForArg)) args :+ extraArg else args

    Try(constructor.newInstance(ctorArgs: _*).asInstanceOf[AnyRef]) match {
      case Success(i) => i
      case Failure(e) =>
        throw new IllegalArgumentException(s"Could not construct ${thingClass.getSimpleName} with arguments ${ctorArgs.toList}", e)
    }
  }
}
