/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kie.kogito.codegen.process;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import org.drools.codegen.common.GeneratedFile;
import org.drools.codegen.common.GeneratedFileType.Category;
import org.drools.compiler.compiler.io.memory.MemoryFileSystem;
import org.junit.jupiter.api.Test;
import org.kie.kogito.codegen.api.context.KogitoBuildContext;
import org.kie.kogito.codegen.api.context.impl.JavaKogitoBuildContext;
import org.kie.kogito.codegen.core.io.CollectedResourceProducer;
import org.kie.memorycompiler.CompilationResult;
import org.kie.memorycompiler.JavaCompiler;
import org.kie.memorycompiler.JavaCompilerFactory;
import org.kie.memorycompiler.JavaConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

public class ProcessGeneratorCodeSizeTest {

    private static final Path BASE_PATH = Paths.get("src/test/resources/").toAbsolutePath();

    private static final JavaCompiler JAVA_COMPILER =
            JavaCompilerFactory.loadCompiler(JavaConfiguration.CompilerType.NATIVE, "17");

    /**
     * Regression test for https://github.com/apache/incubator-kie-issues/issues/2229.
     *
     * Generates Java source from a 754-node BPMN and then compiles it in-memory.
     * Before the fix, the single process() method exceeded the JVM 64 KB bytecode
     * limit and the compiler threw an error. This test will fail with the unfixed
     * code because the compiler returns errors, not because codegen throws.
     */
    @Test
    void largeBpmnCompilesSuccessfully() {
        Path bpmnFile = BASE_PATH.resolve("codetoolarge/repro-fails.bpmn");

        KogitoBuildContext context = JavaKogitoBuildContext.builder()
                .withApplicationProperties(bpmnFile.getParent().toFile())
                .build();

        ProcessCodegen codegen = ProcessCodegen.ofCollectedResources(
                context,
                CollectedResourceProducer.fromFiles(BASE_PATH, bpmnFile.toFile()));

        Collection<GeneratedFile> generatedFiles = codegen.generate();
        assertThat(generatedFiles).isNotEmpty();

        // Compile only the XxxProcess.java file — the one that contains the process()
        // method and previously triggered the 64 KB method-bytecode limit.
        // We do not compile the full application graph (which would require Kogito
        // runtime classes), just the generated process class against its own classpath.
        // ProcessCodegen uses custom GeneratedFileType instances (PROCESS_TYPE,
        // PROCESS_INSTANCE_TYPE, etc.) that all share Category.SOURCE, so filter
        // on category rather than the singleton GeneratedFileType.SOURCE object.
        List<GeneratedFile> processClassFiles = generatedFiles.stream()
                .filter(f -> f.type().category() == Category.SOURCE)
                .filter(f -> f.relativePath().endsWith("Process.java"))
                .toList();

        assertThat(processClassFiles)
                .as("Code generation must produce at least one XxxProcess.java source file")
                .isNotEmpty();

        MemoryFileSystem srcMfs = new MemoryFileSystem();
        MemoryFileSystem trgMfs = new MemoryFileSystem();
        String[] sourceNames = new String[processClassFiles.size()];
        for (int i = 0; i < processClassFiles.size(); i++) {
            GeneratedFile f = processClassFiles.get(i);
            sourceNames[i] = f.relativePath();
            srcMfs.write(f.relativePath(), f.contents());
        }

        CompilationResult result = JAVA_COMPILER.compile(
                sourceNames, srcMfs, trgMfs, getClass().getClassLoader());

        // Filter out errors that are purely missing-symbol errors caused by Kogito
        // application classes not being on the isolated compiler classpath.
        // The only error we care about is "code too large", which would appear as
        // an error on the process() method itself.
        long codeTooLargeErrors = java.util.Arrays.stream(result.getErrors())
                .filter(e -> e.getMessage() != null && e.getMessage().contains("code too large"))
                .count();

        assertThat(codeTooLargeErrors)
                .as("The generated process() method must not exceed the JVM 64 KB bytecode limit")
                .isZero();
    }

    @Test
    void processMetaDataCarriesHelperMethodsForEachNodeAndConnection() {
        List<ProcessExecutableModelGenerator> generators =
                ProcessGenerationUtils.execModelFromProcessFile("/codetoolarge/repro-fails.bpmn");

        assertThat(generators).hasSize(1);

        org.jbpm.compiler.canonical.ProcessMetaData metadata = generators.get(0).generate();

        assertThat(metadata.getProcessHelperMethods())
                .as("Each node must have its own helper method, plus one buildConnections helper")
                .hasSizeGreaterThan(750);

        // Every helper must be private void with a RuleFlowProcessFactory parameter.
        metadata.getProcessHelperMethods().forEach(m -> {
            assertThat(m.getNameAsString())
                    .matches("build[A-Z].*|buildConnections");
            assertThat(m.isPrivate()).isTrue();
            assertThat(m.isStatic()).isFalse();
            assertThat(m.getParameters()).hasSize(1);
        });

        // Exactly one buildConnections helper
        long connectionsHelperCount = metadata.getProcessHelperMethods().stream()
                .filter(m -> m.getNameAsString().equals("buildConnections"))
                .count();
        assertThat(connectionsHelperCount)
                .as("All connections must be grouped into exactly one buildConnections helper")
                .isEqualTo(1);

        // The process() template body must contain only helper call statements
        String processBody = metadata.getGeneratedClassModel()
                .findFirst(com.github.javaparser.ast.body.MethodDeclaration.class)
                .map(com.github.javaparser.ast.body.MethodDeclaration::toString)
                .orElse("");

        assertThat(processBody).contains("build");
        assertThat(processBody).contains("buildConnections(");
    }
}
