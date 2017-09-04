/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.generators.tests.generator;

import com.google.common.collect.Lists;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ContainerUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.generators.util.GeneratorsFileUtil;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.utils.Printer;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

import static kotlin.collections.CollectionsKt.single;

public class TestGenerator {
    private static final Set<String> GENERATED_FILES = ContainerUtil.newHashSet();
    private static final Class RUNNER = JUnit3RunnerWithInners.class;

    private final String baseTestClassPackage;
    private final String suiteClassPackage;
    private final String suiteClassName;
    private final String baseTestClassName;
    private final Collection<TestClassModel> testClassModels;
    private final String testSourceFilePath;

    public TestGenerator(
            @NotNull String baseDir,
            @NotNull String suiteClassPackage,
            @NotNull String suiteClassName,
            @NotNull Class<? extends TestCase> baseTestClass,
            @NotNull Collection<? extends TestClassModel> testClassModels
    ) {
        this.suiteClassPackage = suiteClassPackage;
        this.suiteClassName = suiteClassName;
        this.baseTestClassPackage = baseTestClass.getPackage().getName();
        this.baseTestClassName = baseTestClass.getSimpleName();
        this.testClassModels = Lists.newArrayList(testClassModels);

        this.testSourceFilePath = baseDir + "/" + this.suiteClassPackage.replace(".", "/") + "/" + this.suiteClassName + ".java";

        if (!GENERATED_FILES.add(testSourceFilePath)) {
            throw new IllegalArgumentException("Same test file already generated in current session: " + testSourceFilePath);
        }
    }

    public void generateAndSave() throws IOException {
        StringBuilder out = new StringBuilder();
        Printer p = new Printer(out);

        p.println(FileUtil.loadFile(new File("license/LICENSE.txt")));
        p.println("package ", suiteClassPackage, ";");
        p.println();
        p.println("import com.intellij.testFramework.TestDataPath;");
        p.println("import ", RUNNER.getCanonicalName(), ";");
        p.println("import " + KotlinTestUtils.class.getCanonicalName() + ";");
        p.println("import " + TargetBackend.class.getCanonicalName() + ";");
        if (!suiteClassPackage.equals(baseTestClassPackage)) {
            p.println("import " + baseTestClassPackage + "." + baseTestClassName + ";");
        }
        p.println("import " + TestMetadata.class.getCanonicalName() + ";");
        p.println("import " + RunWith.class.getCanonicalName() + ";");
        p.println();
        p.println("import java.io.File;");
        p.println("import java.util.regex.Pattern;");
        p.println();
        p.println("/** This class is generated by {@link ", KotlinTestUtils.TEST_GENERATOR_NAME, "}. DO NOT MODIFY MANUALLY */");

        generateSuppressAllWarnings(p);

        TestClassModel model;
        if (testClassModels.size() == 1) {
            model = new DelegatingTestClassModel(single(testClassModels)) {
                @NotNull
                @Override
                public String getName() {
                    return suiteClassName;
                }
            };
        }
        else {
            model = new TestClassModel() {
                @NotNull
                @Override
                public Collection<TestClassModel> getInnerTestClasses() {
                    return testClassModels;
                }

                @NotNull
                @Override
                public Collection<MethodModel> getMethods() {
                    return Collections.emptyList();
                }

                @Override
                public boolean isEmpty() {
                    return false;
                }

                @NotNull
                @Override
                public String getName() {
                    return suiteClassName;
                }

                @Override
                public String getDataString() {
                    return null;
                }

                @Nullable
                @Override
                public String getDataPathRoot() {
                    return null;
                }
            };
        }

        generateTestClass(p, model, false, false);

        File testSourceFile = new File(testSourceFilePath);
        GeneratorsFileUtil.writeFileIfContentChanged(testSourceFile, out.toString(), false);
    }

    private void generateTestClass(Printer p, TestClassModel testClassModel, boolean isStatic, boolean isInner) {
        String staticModifier = isStatic ? "static " : "";

        generateMetadata(p, testClassModel);
        generateTestDataPath(p, testClassModel);
        if(!isInner) {
            p.println("@RunWith(", RUNNER.getSimpleName(), ".class)");
        }
        p.println("public " + staticModifier + "class ", testClassModel.getName(), " extends ", baseTestClassName, " {");
        p.pushIndent();

        Collection<MethodModel> testMethods = testClassModel.getMethods();
        Collection<TestClassModel> innerTestClasses = testClassModel.getInnerTestClasses();

        boolean first = true;

        for (Iterator<MethodModel> iterator = testMethods.iterator(); iterator.hasNext(); ) {
            MethodModel methodModel = iterator.next();

            if (!methodModel.shouldBeGenerated()) continue;

            if (first) {
                first = false;
            }
            else {
                p.println();
            }

            generateTestMethod(p, methodModel);
        }

        for (Iterator<TestClassModel> iterator = innerTestClasses.iterator(); iterator.hasNext(); ) {
            TestClassModel innerTestClass = iterator.next();
            if (!innerTestClass.isEmpty()) {
                if (first) {
                    first = false;
                }
                else {
                    p.println();
                }

                generateTestClass(p, innerTestClass, true, true);
            }
        }

        p.popIndent();
        p.println("}");
    }

    private static void generateTestMethod(Printer p, MethodModel methodModel) {
        generateMetadata(p, methodModel);
        
        methodModel.generateSignature(p);
        p.printWithNoIndent(" {");
        p.println();
        
        p.pushIndent();

        methodModel.generateBody(p);

        p.popIndent();
        p.println("}");
    }

    private static void generateMetadata(Printer p, TestEntityModel testDataSource) {
        String dataString = testDataSource.getDataString();
        if (dataString != null) {
            p.println("@TestMetadata(\"", dataString, "\")");
        }
    }

    private static void generateTestDataPath(Printer p, TestClassModel testClassModel) {
        String dataPathRoot = testClassModel.getDataPathRoot();
        if (dataPathRoot != null) {
            p.println("@TestDataPath(\"", dataPathRoot, "\")");
        }
    }

    private static void generateSuppressAllWarnings(Printer p) {
        p.println("@SuppressWarnings(\"all\")");
    }
}
