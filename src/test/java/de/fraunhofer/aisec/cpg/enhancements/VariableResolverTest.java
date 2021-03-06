/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */

package de.fraunhofer.aisec.cpg.enhancements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.fraunhofer.aisec.cpg.TestUtils;
import de.fraunhofer.aisec.cpg.TranslationConfiguration;
import de.fraunhofer.aisec.cpg.TranslationManager;
import de.fraunhofer.aisec.cpg.graph.DeclaredReferenceExpression;
import de.fraunhofer.aisec.cpg.graph.FieldDeclaration;
import de.fraunhofer.aisec.cpg.graph.MemberExpression;
import de.fraunhofer.aisec.cpg.graph.MethodDeclaration;
import de.fraunhofer.aisec.cpg.graph.ReturnStatement;
import de.fraunhofer.aisec.cpg.graph.TranslationUnitDeclaration;
import de.fraunhofer.aisec.cpg.graph.VariableDeclaration;
import de.fraunhofer.aisec.cpg.helpers.Util;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class VariableResolverTest {

  private List<TranslationUnitDeclaration> analyze() throws Exception {
    Path topLevel = Path.of("src", "test", "resources", "variables");
    File[] files =
        Files.walk(topLevel, Integer.MAX_VALUE)
            .map(Path::toFile)
            .filter(File::isFile)
            .filter(f -> f.getName().endsWith(".java"))
            .sorted()
            .toArray(File[]::new);

    TranslationConfiguration config =
        TranslationConfiguration.builder()
            .sourceFiles(files)
            .topLevel(topLevel.toFile())
            .defaultPasses()
            .debugParser(true)
            .failOnError(true)
            .build();

    TranslationManager analyzer = TranslationManager.builder().config(config).build();

    return analyzer.analyze().get().getTranslationUnits();
  }

  @Test
  public void testFields() throws Exception {
    List<TranslationUnitDeclaration> result = analyze();
    List<MethodDeclaration> methods = Util.subnodesOfType(result, MethodDeclaration.class);
    List<FieldDeclaration> fields = Util.subnodesOfType(result, FieldDeclaration.class);
    FieldDeclaration field = TestUtils.findByName(fields, "field");

    MethodDeclaration getField = TestUtils.findByName(methods, "getField");
    ReturnStatement returnStatement = Util.subnodesOfType(getField, ReturnStatement.class).get(0);
    assertEquals(field, ((MemberExpression) returnStatement.getReturnValue()).getMember());

    MethodDeclaration noShadow = TestUtils.findByName(methods, "getField");
    returnStatement = Util.subnodesOfType(noShadow, ReturnStatement.class).get(0);
    assertEquals(field, ((MemberExpression) returnStatement.getReturnValue()).getMember());
  }

  @Test
  public void testLocalVars() throws Exception {
    List<TranslationUnitDeclaration> result = analyze();
    List<MethodDeclaration> methods = Util.subnodesOfType(result, MethodDeclaration.class);
    List<FieldDeclaration> fields = Util.subnodesOfType(result, FieldDeclaration.class);
    FieldDeclaration field = TestUtils.findByName(fields, "field");

    MethodDeclaration getLocal = TestUtils.findByName(methods, "getLocal");
    ReturnStatement returnStatement = Util.subnodesOfType(getLocal, ReturnStatement.class).get(0);
    VariableDeclaration local = Util.subnodesOfType(getLocal, VariableDeclaration.class).get(0);
    DeclaredReferenceExpression returnValue =
        (DeclaredReferenceExpression) returnStatement.getReturnValue();
    assertNotEquals(field, returnValue.getRefersTo());
    assertEquals(local, returnValue.getRefersTo());

    MethodDeclaration getShadow = TestUtils.findByName(methods, "getShadow");
    returnStatement = Util.subnodesOfType(getShadow, ReturnStatement.class).get(0);
    local = Util.subnodesOfType(getShadow, VariableDeclaration.class).get(0);
    returnValue = (DeclaredReferenceExpression) returnStatement.getReturnValue();
    assertNotEquals(field, returnValue.getRefersTo());
    assertEquals(local, returnValue.getRefersTo());
  }
}
