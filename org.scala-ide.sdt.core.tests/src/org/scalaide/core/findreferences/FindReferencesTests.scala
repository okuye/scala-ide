package org.scalaide.core
package findreferences

import org.scalaide.core.IScalaProject
import org.scalaide.util.ScalaWordFinder
import org.scalaide.core.internal.jdt.model._
import org.scalaide.logging.HasLogger
import testsetup.FileUtils
import testsetup.SDTTestUtils
import testsetup.SearchOps
import testsetup.TestProjectSetup
import org.eclipse.core.resources.IResource
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.Path
import org.eclipse.jdt.core.IField
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.core.IMethod
import org.eclipse.jdt.core.IType
import org.eclipse.jdt.core.Signature
import org.eclipse.jdt.internal.core.JavaElement
import org.eclipse.jdt.internal.core.SourceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(classOf[JUnit4])
class FindReferencesTests extends FindReferencesTester with HasLogger {
  private final val TestProjectName = "find-references"

  private var projectSetup: TestProjectSetup = _

  def project: IScalaProject = projectSetup.project

  private var typeCheckUnitBeforeRunningTest: Boolean = _

  @Before
  def setUp(): Unit = {
    typeCheckUnitBeforeRunningTest = false
  }

  @Before
  def createProject(): Unit = {
    val scalaProject = SDTTestUtils.createProjectInWorkspace(TestProjectName, withSourceRoot = true)
    projectSetup = new TestProjectSetup(TestProjectName) {
      override lazy val project = scalaProject
    }
  }

  @After
  def deleteProject(): Unit = {
    SDTTestUtils.deleteProjects(project)
  }

  def runTest(testProjectName: String, sourceName: String, testDefinition: TestBuilder): Unit = {
    val testWorkspaceLocation = SDTTestUtils.sourceWorkspaceLoc(projectSetup.bundleName)
    val findReferencesTestWorkspace = testWorkspaceLocation.append(new Path(TestProjectName))
    val testProject = findReferencesTestWorkspace.append(testProjectName)

    mirrorContentOf(testProject)

    runTest(sourceName, testDefinition.testMarker, testDefinition.toExpectedTestResult)
  }

  private def mirrorContentOf(sourceProjectLocation: IPath): Unit = {
    val target = project.underlying.getLocation.toFile
    val from = sourceProjectLocation.toFile

    FileUtils.copyDirectory(from, target)

    project.underlying.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor)
  }

  private def runTest(source: String, marker: String, expected: TestResult): Unit = {
    // Set up
    val unit = projectSetup.scalaCompilationUnit(source)
    // FIXME: This should not be necessary, but if not done then tests randomly fail:
    //        "scala.tools.nsc.interactive.NoSuchUnitError: no unit found for file XXX"
    projectSetup.reload(unit)
    if (typeCheckUnitBeforeRunningTest) projectSetup.waitUntilTypechecked(unit)

    val offsets = projectSetup.findMarker(marker) in unit

    if (offsets.isEmpty) fail("Test failed for source `%s`. Reason: could not find test marker `%s` in the sourcefile.".format(source, marker))
    else if (offsets.length > 1) fail("Test failed for source `%s`. Reason: only one occurrence of `%s` per test file is allowed".format(source, marker))

    val offset = offsets.head

    val wordRegion = ScalaWordFinder.findWord(unit.getContents, offset)
    val word = new String(unit.getContents.slice(wordRegion.getOffset, wordRegion.getOffset + wordRegion.getLength))

    if (word.trim.isEmpty) fail("No word found at offset: " + offset)

    logger.debug("Searching references of (%s) @ %d".format(word, offset))

    val elements = unit.codeSelect(wordRegion.getOffset, wordRegion.getLength)
    if (elements.isEmpty) fail("cannot find code element for " + word)
    val element = elements(0).asInstanceOf[JavaElement]

    // SUT
    val matches = SearchOps.findReferences(element)

    // verify
    val convertedMatches = matches.map(searchMatch => jdtElement2testElement(searchMatch.getElement().asInstanceOf[JavaElement])).toSet
    val result = TestResult(jdtElement2testElement(element), convertedMatches)
    assertEquals(expected, result)
  }

  private def jdtElement2testElement(e: JavaElement): Element = {
    val testElement: String => Element = e match {
      case _: ScalaDefElement       => Method.apply _
      case _: ScalaAccessorElement  => Method.apply _
      case _: ScalaVarElement       => FieldVar.apply _
      case _: ScalaValElement       => FieldVal.apply _
      case _: ScalaClassElement     => Clazz.apply _
      case _: ScalaTypeElement      => TypeAlias.apply _
      case _: ScalaTypeFieldElement => TypeAlias.apply _
      case _: ScalaModuleElement    => Module.apply _
      case _: SourceType            => Clazz.apply _
      case _ =>
        val msg = "Don't know how to convert element `%s` of type `%s`".format(e.getElementName, e.getClass)
        throw new IllegalArgumentException(msg)
    }
    testElement(fullName(e))
  }

  private def fullName(e: IJavaElement): String = e match {
    case tpe: IType =>
      val name = tpe.getFullyQualifiedName
      name
    case field: IField =>
      val qualificator = fullName(field.getDeclaringType) + "."
      val name = field.getElementName
      qualificator + name
    case method: IMethod =>
      val qualificator = fullName(method.getDeclaringType) + "."
      val name = method.getElementName()
      val parmsTpes = method.getParameterTypes.map { t =>
        val pkg = Signature.getSignatureQualifier(t)
        (if (pkg.nonEmpty) pkg + "." else "") + Signature.getSignatureSimpleName(t)
      }.mkString(", ")

      val params = "(" + parmsTpes + ")"
      qualificator + name + params
  }

  @Test
  def findReferencesOfClassFieldVar_bug1000067_1(): Unit = {
    val expected = fieldVar("Referred.aVar") isReferencedBy method("Referring.anotherMethod") and method("Referring.yetAnotherMethod")
    runTest("bug1000067_1", "FindReferencesOfClassFieldVar.scala", expected)
  }

  @Test
  def findReferencesOfClassMethod_bug1000067_2(): Unit = {
    val expected = method("Referred.aMethod") isReferencedBy method("Referring.anotherMethod") and method("Referring.yetAnotherMethod")
    runTest("bug1000067_2", "FindReferencesOfClassMethod.scala", expected)
  }

  @Test
  def findReferencesOfClassFieldVal_bug1000067_3(): Unit = {
    val expected = fieldVal("Referred.aVal") isReferencedBy method("Referring.anotherMethod") and method("Referring.yetAnotherMethod")
    runTest("bug1000067_3", "FindReferencesOfClassFieldVal.scala", expected)
  }

  @Test
  def findReferencesOfClassFieldLazyVal(): Unit = {
    val expected = fieldVal("Foo.lazyX") isReferencedBy method("Bar.meth")
    runTest("lazy-val", "FindReferencesOfClassFieldLazyVal.scala", expected)
  }

  @Test
  def findReferencesOfClassConstructor_bug1000063_1(): Unit = {
    val expected = clazz("ReferredClass") isReferencedBy method("ReferringClass.foo") and method("ReferringClass.bar")
    runTest("bug1000063_1", "FindReferencesOfClassConstructor.scala", expected)
  }

  @Test
  def findReferencesOfClassTypeInMethodTypeBound_bug1000063_2(): Unit = {
    val expected = clazz("ReferredClass") isReferencedBy clazz("ReferringClass") and typeAlias("ReferringClass.typedSet") and method("ReferringClass.foo")
    runTest("bug1000063_2", "FindReferencesOfClassType.scala", expected)
  }

  @Test
  def findReferencesOfClassType_bug1001084(): Unit = {
    val expected = clazz("Foo") isReferencedBy clazz("Bar")
    runTest("bug1001084", "FindReferencesOfClassType.scala", expected)
  }

  @Test
  def findReferencesInsideCompanionObject_ex1(): Unit = {
    val expected = fieldVal("Foo$.ss") isReferencedBy moduleConstructor("Foo")
    runTest("ex1", "Ex1.scala", expected)
  }

  @Test
  def findReferencesInConstructorSuperCall(): Unit = {
    val expected = fieldVal("foo.Bar$.vvvv") isReferencedBy clazzConstructor("foo.Foo")
    runTest("super", "foo/Bar.scala", expected)
  }

  @Test
  def bug1001135(): Unit = {
    val expected = method("foo.Bar$.configure", List("java.lang.String")) isReferencedBy method("foo.Foo.configure")
    runTest("bug1001135", "foo/Bar.scala", expected)
  }

  @Test
  def findReferencesInClassFields(): Unit = {
    val expected = fieldVal("Bar$.vvvv") isReferencedBy fieldVal("Foo.vvvv")
    runTest("field-ref", "Bar.scala", expected)
  }

  @Test
  def findReferencesOfCurriedMethod_bug1001146(): Unit = {
    val expected = method("util.EclipseUtils$.workspaceRunnableIn", List("java.lang.String", "java.lang.Object", "scala.Function1<java.lang.Object,scala.runtime.BoxedUnit>")) isReferencedBy method("util.FileUtils$.foo")
    runTest("bug1001146", "util/EclipseUtils.scala", expected)
  }

  @Test
  def findReferencesOfMethodDeclaredWithDefaultArgs_bug1001146_1(): Unit = {
    val expected = method("util.EclipseUtils$.workspaceRunnableIn", List("java.lang.String", "java.lang.Object", "scala.Function1<java.lang.Object,scala.runtime.BoxedUnit>")) isReferencedBy method("util.FileUtils$.foo")
    runTest("bug1001146_1", "util/EclipseUtils.scala", expected)
  }

  @Test
  def findReferencesOfMethodInsideAnonymousFunction(): Unit = {
    val expected = method("Foo.foo") isReferencedBy moduleConstructor("Bar")
    runTest("anon-fun", "Foo.scala", expected)
  }

  @Test
  def findReferencesOfAnonymousClass(): Unit = {
    val expected = clazz("Foo") isReferencedBy fieldVal("Bar$.f")
    runTest("anon-class", "Foo.scala", expected)
  }

  @Test
  def findReferencesOfAbstractMember(): Unit = {
    val expected = method("Foo.obj") isReferencedBy method("Foo.foo")
    runTest("abstract-member", "Foo.scala", expected)
  }

  @Test
  def findReferencesOfVarSetter(): Unit = {
    val expected = fieldVar("Foo.obj1") isReferencedBy clazzConstructor("Bar") and fieldVal("Bar.bar") and method("Bar.bar2")
    runTest("var_ref", "Bar.scala", expected)
  }

  @Test
  def findReferencesOfVarSetterAfterUnitIsTypehecked(): Unit = {
    typeCheckUnitBeforeRunningTest = true
    val expected = fieldVar("Foo.obj1") isReferencedBy clazzConstructor("Bar") and fieldVal("Bar.bar") and method("Bar.bar2")
    runTest("var_ref", "Bar.scala", expected)
  }

  @Test
  def findReferencesOfMethodWithPrimitiveArgument_bug1001167_1(): Unit = {
    val expected = method("A.testA1", List("int")) isReferencedBy method("A.testA2")
    runTest("bug1001167_1", "A.scala", expected)
  }
}
