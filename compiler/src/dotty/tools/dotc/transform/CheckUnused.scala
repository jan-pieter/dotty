package dotty.tools.dotc.transform

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.TreeTraverser
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.ImportSelector
import dotty.tools.dotc.config.ScalaSettings
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators.i
import dotty.tools.dotc.core.Flags.Given
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.report
import dotty.tools.dotc.reporting.Message
import dotty.tools.dotc.typer.ImportInfo
import dotty.tools.dotc.util.Property

/**
 * A compiler phase that checks for unused imports or definitions
 *
 * Basically, it gathers definition/imports and their usage. If a
 * definition/imports does not have any usage, then it is reported.
 */
class CheckUnused extends Phase:
  import CheckUnused.UnusedData

  private val _key = Property.Key[UnusedData]

  override def phaseName: String = CheckUnused.phaseName

  override def description: String = CheckUnused.description

  override def run(using Context): Unit =
    val tree = ctx.compilationUnit.tpdTree
    val data = UnusedData(ctx)
    if data.neededChecks.nonEmpty then
      // Execute checks if there exists at lease one config
      val fresh = ctx.fresh.setProperty(_key, data)
      traverser.traverse(tree)(using fresh)
      reportUnusedImport(data.getUnused)

  /**
   * This traverse is the **main** component of this phase
   *
   * It traverse the tree the tree and gather the data in the
   * corresponding context property
   */
  private def traverser = new TreeTraverser {
    import tpd._

    override def traverse(tree: tpd.Tree)(using Context): Unit = tree match
      case imp@Import(_, sels) => sels.foreach { s =>
          ctx.property(_key).foreach(_.registerImport(imp))
        }
      case ident: Ident =>
        val id = ident.symbol.id
        ctx.property(_key).foreach(_.registerUsed(id))
        traverseChildren(tree)
      case sel: Select =>
        val id = sel.symbol.id
        ctx.property(_key).foreach(_.registerUsed(id))
        traverseChildren(tree)
      case tpd.Block(_,_) | tpd.Template(_,_,_,_) =>
        ctx.property(_key).foreach(_.pushScope())
        traverseChildren(tree)
        ctx.property(_key).foreach(_.popScope())
      case _ => traverseChildren(tree)

  }

  private def reportUnusedImport(sels: List[ImportSelector])(using Context) =
    if ctx.settings.WunusedHas.imports then
      sels.foreach { s =>
        report.warning(i"unused import", s.srcPos)
      }
end CheckUnused

object CheckUnused:
  val phaseName: String = "check unused"
  val description: String = "check for unused elements"

  /**
    * Various supported configuration chosen by -Wunused:<config>
    */
  private enum UnusedConfig:
    case UnusedImports
    // TODO : handle other cases like unused local def

  /**
   * A stateful class gathering the infos on :
   * - imports
   * - definitions
   * - usage
   */
  private class UnusedData(initctx: Context):
    import collection.mutable.{Set => MutSet, Map => MutMap, Stack, ListBuffer}

    val neededChecks =
      import UnusedConfig._
      val hasConfig = initctx.settings.WunusedHas
      val mut = MutSet[UnusedConfig]()
      if hasConfig.imports(using initctx) then
        mut += UnusedImports
      mut.toSet

    private val used = Stack(MutSet[Int]())
    private val impInScope = Stack(MutMap[Int, ListBuffer[ImportSelector]]())
    private val unused = ListBuffer[ImportSelector]()

    private def isImportExclusion(sel: ImportSelector): Boolean = sel.renamed match
      case ident@untpd.Ident(name) => name == StdNames.nme.WILDCARD
      case _ => false

    /** Register the id of a found (used) symbol */
    def registerUsed(id: Int): Unit =
        used.top += id

    /** Register an import */
    def registerImport(imp: tpd.Import)(using Context): Unit =
      val tpd.Import(tree, sels) = imp
      val map = impInScope.top
      val entries = sels.flatMap{ s =>
        if s.isWildcard then
          tree.tpe.allMembers
            .filter(m => m.symbol.is(Given) == s.isGiven) // given imports
            .map(_.symbol.id -> s)
        else
          val id = tree.tpe.member(s.name.toTermName).symbol.id
          val typeId = tree.tpe.member(s.name.toTypeName).symbol.id
          List(id -> s, typeId -> s)
      }
      entries.foreach{(id, sel) =>
        map.get(id) match
          case None => map.put(id, ListBuffer(sel))
          case Some(value) => value += sel
      }

    /** enter a new scope */
    def pushScope(): Unit =
      used.push(MutSet())
      impInScope.push(MutMap())

    /** leave the current scope */
    def popScope(): Unit =
      val usedImp = MutSet[ImportSelector]()
      val poppedImp = impInScope.pop()
      val notDefined = used.pop().filter{id =>
        poppedImp.remove(id) match
          case None => true
          case Some(value) =>
            usedImp.addAll(value)
            false
      }
      if used.size > 0 then
        used.top.addAll(notDefined)
      poppedImp.values.flatten.foreach{ sel =>
        // If **any** of the entities used by the import is used,
        // do not add to the `unused` Set
        if !usedImp(sel) then
          unused += sel
      }

    /**
     * Leave the scope and return a `List` of unused `ImportSelector`s
     *
     * The given `List` is sorted by line and then column of the position
     */
    def getUnused(using Context): List[ImportSelector] =
      popScope()
      unused.toList.sortBy{ sel =>
        val pos = sel.srcPos.sourcePos
        (pos.line, pos.column)
      }

  end UnusedData
end CheckUnused

