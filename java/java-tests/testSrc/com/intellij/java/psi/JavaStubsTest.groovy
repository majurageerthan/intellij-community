/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.java.psi

import com.intellij.codeInsight.AnnotationTargetUtil
import com.intellij.codeInspection.dataFlow.ControlFlowAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.PsiClassImpl
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase

import java.util.concurrent.Callable

class JavaStubsTest extends LightCodeInsightFixtureTestCase {

  void "test resolve from annotation method default"() {
    def cls = myFixture.addClass("""
      public @interface BrokenAnnotation {
        enum Foo {DEFAULT, OTHER}
        Foo value() default Foo.DEFAULT;
      }
      """.stripIndent())

    def file = cls.containingFile as PsiFileImpl
    assert file.stub

    def ref = (cls.methods[0] as PsiAnnotationMethod).defaultValue
    assert file.stub

    assert ref instanceof PsiReferenceExpression
    assert ref.resolve() == cls.innerClasses[0].fields[0]
    assert file.stub
  }

  void "test literal annotation value"() {
    def cls = myFixture.addClass("""
      class Foo {
        @org.jetbrains.annotations.Contract(pure=true)
        native int foo();
      }
      """.stripIndent())

    def file = cls.containingFile as PsiFileImpl
    assert ControlFlowAnalyzer.isPure(cls.methods[0])
    assert file.stub
    assert !file.contentsLoaded
  }

  void "test local variable annotation doesn't cause stub-ast switch"() {
    def cls = myFixture.addClass("""
      class Foo {
        @Anno int foo() {
          @Anno int var = 2;
        }
      }
      @interface Anno {}
      """)

    def file = cls.containingFile as PsiFileImpl
    assert AnnotatedElementsSearch.searchPsiMethods(myFixture.findClass("Anno"), GlobalSearchScope.allScope(project)).size() == 1
    assert file.stub
    assert !file.contentsLoaded
  }

  void "test applying type annotations"() {
    def cls = myFixture.addClass("""
      import java.lang.annotation.*;
      class Foo {
        @Target(ElementType.TYPE_USE)
        @interface TA { String value(); }

        private @TA String f1;

        private static @TA int m1(@TA int p1) { return 0; }
      }
      """.stripIndent())

    def f1 = cls.fields[0].type
    def m1 = cls.methods[0].returnType
    def p1 = cls.methods[0].parameterList.parameters[0].type
    assert (cls as PsiClassImpl).stub

    assert f1.getCanonicalText(true) == "java.lang.@Foo.TA String"
    assert m1.getCanonicalText(true) == "@Foo.TA int"
    assert p1.getCanonicalText(true) == "@Foo.TA int"
  }

  void "test containing class of a local class is null"() {
    def foo = myFixture.addClass("class Foo {{ class Bar extends Foo {} }}")
    def bar = ClassInheritorsSearch.search(foo).findFirst()

    def file = (PsiFileImpl)foo.containingFile
    assert !file.contentsLoaded

    assert bar.containingClass == null
    assert !file.contentsLoaded

    bar.node
    assert bar.containingClass == null
    assert file.contentsLoaded
  }

  void "test stub-based super class type parameter resolve"() {
    for (int i = 0; i < 100; i++) {
      def foo = myFixture.addClass("class Foo$i<T> {}")
      def bar = myFixture.addClass("class Bar$i<T> extends Foo$i<T> {}")

      def app = ApplicationManager.application
      app.executeOnPooledThread({ ReadAction.compute { bar.node } })
      def superType = app.executeOnPooledThread({ ReadAction.compute { bar.superTypes[0] }} as Callable<PsiClassType>).get()
      assert foo == superType.resolve()
      assert bar.typeParameters[0] == PsiUtil.resolveClassInClassTypeOnly(superType.parameters[0])
    }
  }

  void "test default annotation attribute name"() {
    def cls = myFixture.addClass('@Anno("foo") class Foo {}')
    def file = (PsiFileImpl)cls.containingFile
    assert !file.contentsLoaded

    def attr = cls.modifierList.annotations[0].parameterList.attributes[0]
    assert attr.name == null
    assert !file.contentsLoaded

    attr.node
    assert attr.name == null
  }

  void "test determine annotation target without AST"() {
    def cls = myFixture.addClass('''
import java.lang.annotation.*;
@Anno class Some {} 
@Target(ElementType.METHOD) @interface Anno {}''')
    assert 'Some' == cls.name
    assert !AnnotationTargetUtil.isTypeAnnotation(cls.modifierList.annotations[0])
    assert !((PsiFileImpl) cls.containingFile).contentsLoaded
  }

  void "test parameter list count"() {
    def list = myFixture.addClass('class Cls { void foo(a) {} }').methods[0].parameterList
    assert list.parametersCount == list.parameters.size()
  }

  void "test deprecated enum constant"() {
    def cls = myFixture.addClass("enum Foo { c1, @Deprecated c2, /** @deprecated */ c3 }")
    assert !((PsiFileImpl) cls.containingFile).contentsLoaded

    assert !cls.fields[0].deprecated
    assert cls.fields[1].deprecated
    assert cls.fields[2].deprecated

    assert !((PsiFileImpl) cls.containingFile).contentsLoaded
  }

  void "test breaking and adding import does not cause stub AST mismatch"() {
    def file = myFixture.addFileToProject("a.java", "import foo.*; import bar.*; class Foo {}") as PsiJavaFile
    def another = myFixture.addClass("package zoo; public class Another {}")
    WriteCommandAction.runWriteCommandAction(project) { 
      file.viewProvider.document.insertString(file.text.indexOf('import'), 'x')
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      file.importClass(another)
    }
    PsiTestUtil.checkStubsMatchText(file)
  }

  void "test adding type before method call does not cause stub AST mismatch"() {
    def file = myFixture.addFileToProject("a.java", """
class Foo {
  void foo() {
    something();
    call();
  }
}
""") as PsiJavaFile
    WriteCommandAction.runWriteCommandAction(project) { 
      file.viewProvider.document.insertString(file.text.indexOf('call'), 'char ')
      PsiTestUtil.checkStubsMatchText(file)
    }
  }
}