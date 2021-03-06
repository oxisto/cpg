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

package de.fraunhofer.aisec.cpg.frontends.java;

import com.github.javaparser.Position;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import de.fraunhofer.aisec.cpg.frontends.Handler;
import de.fraunhofer.aisec.cpg.graph.ConstructorDeclaration;
import de.fraunhofer.aisec.cpg.graph.Declaration;
import de.fraunhofer.aisec.cpg.graph.EnumConstantDeclaration;
import de.fraunhofer.aisec.cpg.graph.EnumDeclaration;
import de.fraunhofer.aisec.cpg.graph.FieldDeclaration;
import de.fraunhofer.aisec.cpg.graph.MethodDeclaration;
import de.fraunhofer.aisec.cpg.graph.NodeBuilder;
import de.fraunhofer.aisec.cpg.graph.ParamVariableDeclaration;
import de.fraunhofer.aisec.cpg.graph.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.Region;
import de.fraunhofer.aisec.cpg.graph.Type;
import de.fraunhofer.aisec.cpg.passes.scopes.RecordScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeclarationHandler
    extends Handler<Declaration, BodyDeclaration, JavaLanguageFrontend> {

  public DeclarationHandler(JavaLanguageFrontend lang) {
    super(Declaration::new, lang);
    map.put(
        com.github.javaparser.ast.body.MethodDeclaration.class,
        decl -> handleMethodDeclaration((com.github.javaparser.ast.body.MethodDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.ConstructorDeclaration.class,
        decl ->
            handleConstructorDeclaration(
                (com.github.javaparser.ast.body.ConstructorDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class,
        decl ->
            handleClassOrInterfaceDeclaration(
                (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.FieldDeclaration.class,
        decl -> handleFieldDeclaration((com.github.javaparser.ast.body.FieldDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.EnumDeclaration.class,
        decl -> handleEnumDeclaration((com.github.javaparser.ast.body.EnumDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.EnumConstantDeclaration.class,
        decl ->
            handleEnumConstantDeclaration(
                (com.github.javaparser.ast.body.EnumConstantDeclaration) decl));
  }

  private static void addImplicitReturn(BlockStmt body) {
    NodeList<Statement> statements = body.getStatements();

    // get the last statement
    Statement lastStatement = null;
    if (!statements.isEmpty()) {
      lastStatement = statements.get(statements.size() - 1);
    }
    // make sure, method contains a return statement
    if (lastStatement == null || !lastStatement.isReturnStmt()) {
      body.addStatement(new ReturnStmt());
    }
  }

  public ConstructorDeclaration handleConstructorDeclaration(
      com.github.javaparser.ast.body.ConstructorDeclaration constructorDecl) {
    ResolvedConstructorDeclaration resolvedConstructor = constructorDecl.resolve();

    de.fraunhofer.aisec.cpg.graph.ConstructorDeclaration declaration =
        NodeBuilder.newConstructorDeclaration(
            resolvedConstructor.getName(), constructorDecl.toString());
    lang.getScopeManager().addValueDeclaration(declaration);

    lang.getScopeManager().enterScope(declaration);
    declaration
        .getThrowsTypes()
        .addAll(
            constructorDecl.getThrownExceptions().stream()
                .map(type -> new Type(type.asString()))
                .collect(Collectors.toList()));

    for (Parameter parameter : constructorDecl.getParameters()) {
      ParamVariableDeclaration param =
          NodeBuilder.newMethodParameterIn(
              parameter.getNameAsString(),
              this.lang.getTypeAsGoodAsPossible(parameter, parameter.resolve()),
              parameter.isVarArgs(),
              parameter.toString());

      declaration.getParameters().add(param);

      lang.setCodeAndRegion(param, parameter);
      lang.getScopeManager().addValueDeclaration(param);
    }

    Type type =
        new Type(
            lang.getScopeManager()
                .getFirstScopeThat(RecordScope.class::isInstance)
                .getAstNode()
                .getName());
    declaration.setType(type);

    // check, if constructor has body (i.e. its not abstract or something)
    BlockStmt body = constructorDecl.getBody();

    addImplicitReturn(body);

    declaration.setBody(this.lang.getStatementHandler().handle(body));
    lang.getScopeManager().leaveScope(declaration);
    return declaration;
  }

  public MethodDeclaration handleMethodDeclaration(
      com.github.javaparser.ast.body.MethodDeclaration methodDecl) {
    ResolvedMethodDeclaration resolvedMethod = methodDecl.resolve();

    de.fraunhofer.aisec.cpg.graph.MethodDeclaration functionDeclaration =
        NodeBuilder.newMethodDeclaration(
            resolvedMethod.getName(), methodDecl.toString(), methodDecl.isStatic());
    lang.getScopeManager().enterScope(functionDeclaration);

    functionDeclaration
        .getThrowsTypes()
        .addAll(
            methodDecl.getThrownExceptions().stream()
                .map(type -> new Type(type.asString()))
                .collect(Collectors.toList()));

    for (Parameter parameter : methodDecl.getParameters()) {
      ParamVariableDeclaration param =
          NodeBuilder.newMethodParameterIn(
              parameter.getNameAsString(),
              this.lang.getTypeAsGoodAsPossible(parameter, parameter.resolve()),
              parameter.isVarArgs(),
              parameter.toString());

      functionDeclaration.getParameters().add(param);
      lang.setCodeAndRegion(param, parameter);
      lang.getScopeManager().addValueDeclaration(param);
    }

    functionDeclaration.setType(
        this.lang.getReturnTypeAsGoodAsPossible(methodDecl, resolvedMethod));

    // check, if method has body (i.e. its not abstract or something)
    Optional<BlockStmt> o = methodDecl.getBody();

    if (o.isEmpty()) {
      lang.getScopeManager().leaveScope(functionDeclaration);
      return functionDeclaration;
    }

    BlockStmt body = o.get();

    addImplicitReturn(body);

    functionDeclaration.setBody(this.lang.getStatementHandler().handle(body));
    lang.getScopeManager().leaveScope(functionDeclaration);
    return functionDeclaration;
  }

  public RecordDeclaration handleClassOrInterfaceDeclaration(
      ClassOrInterfaceDeclaration classInterDecl) {
    // TODO: support other kinds, such as interfaces
    String name = classInterDecl.getNameAsString();

    // Todo adapt name using a new type of scope "Namespace/Package scope"
    // if (packageDeclaration != null) {
    //  name = packageDeclaration.getNameAsString() + "." + name;
    // }
    name = getAbsoluteName(name);

    List<de.fraunhofer.aisec.cpg.graph.Type> superTypes =
        Stream.of(classInterDecl.getExtendedTypes(), classInterDecl.getImplementedTypes())
            .flatMap(Collection::stream)
            .map(this.lang::getTypeAsGoodAsPossible)
            .collect(Collectors.toList());

    // add a type declaration
    RecordDeclaration recordDeclaration =
        NodeBuilder.newRecordDeclaration(name, superTypes, "class", classInterDecl.toString());

    Map<Boolean, List<String>> partitioned =
        this.lang.getContext().getImports().stream()
            .collect(
                Collectors.partitioningBy(
                    ImportDeclaration::isStatic,
                    Collectors.mapping(
                        i -> {
                          String iName = i.getNameAsString();
                          // we need to ensure that x.* imports really preserve the asterisk!
                          if (i.isAsterisk() && !iName.endsWith(".*")) {
                            iName += ".*";
                          }
                          return iName;
                        },
                        Collectors.toList())));
    recordDeclaration.setStaticImportStatements(partitioned.get(true));
    recordDeclaration.setImportStatements(partitioned.get(false));

    // Todo lang.getScopeManager().addValueDeclaration(recordDeclaration); but record dont hold
    // record declarations currently
    lang.getScopeManager().enterScope(recordDeclaration);

    de.fraunhofer.aisec.cpg.graph.FieldDeclaration thisDeclaration =
        NodeBuilder.newFieldDeclaration(
            "this",
            new de.fraunhofer.aisec.cpg.graph.Type(name),
            new ArrayList<>(),
            "this",
            new Region(-1, -1, -1, -1),
            null);
    recordDeclaration.getFields().add(thisDeclaration);
    lang.getScopeManager().addValueDeclaration(thisDeclaration);

    // TODO: 'this' identifier for multiple instances?
    for (BodyDeclaration<?> decl : classInterDecl.getMembers()) {
      if (decl instanceof com.github.javaparser.ast.body.FieldDeclaration) {
        handle(decl); // will be added via the scopemanager
      } else if (decl instanceof com.github.javaparser.ast.body.MethodDeclaration) {
        recordDeclaration
            .getMethods()
            .add((de.fraunhofer.aisec.cpg.graph.MethodDeclaration) handle(decl));
      } else if (decl instanceof com.github.javaparser.ast.body.ConstructorDeclaration) {
        recordDeclaration
            .getConstructors()
            .add((de.fraunhofer.aisec.cpg.graph.ConstructorDeclaration) handle(decl));
      } else if (decl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) {
        recordDeclaration
            .getRecords()
            .add((de.fraunhofer.aisec.cpg.graph.RecordDeclaration) handle(decl));
      } else {
        log.debug(
            "Member {} of type {} is something that we do not parse yet: {}",
            decl,
            recordDeclaration.getName(),
            decl.getClass().getSimpleName());
      }
    }

    if (recordDeclaration.getConstructors().isEmpty()) {
      de.fraunhofer.aisec.cpg.graph.ConstructorDeclaration constructorDeclaration =
          NodeBuilder.newConstructorDeclaration(
              recordDeclaration.getName(), recordDeclaration.getName());
      recordDeclaration.getConstructors().add(constructorDeclaration);
      lang.getScopeManager().addValueDeclaration(constructorDeclaration);
    }
    lang.getScopeManager().leaveScope(recordDeclaration);
    return recordDeclaration;
  }

  public FieldDeclaration handleFieldDeclaration(
      com.github.javaparser.ast.body.FieldDeclaration fieldDecl) {

    // TODO: can  field have more than one variable?
    VariableDeclarator variable = fieldDecl.getVariable(0);
    List<String> modifiers =
        fieldDecl.getModifiers().stream()
            .map(modifier -> modifier.getKeyword().asString())
            .collect(Collectors.toList());

    Region region = getRegion(fieldDecl);

    de.fraunhofer.aisec.cpg.graph.Expression initializer =
        (de.fraunhofer.aisec.cpg.graph.Expression)
            variable.getInitializer().map(this.lang.getExpressionHandler()::handle).orElse(null);
    de.fraunhofer.aisec.cpg.graph.Type type;
    try {
      type = new de.fraunhofer.aisec.cpg.graph.Type(variable.resolve().getType().describe());
    } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
      String t = this.lang.recoverTypeFromUnsolvedException(e);
      if (t == null) {
        log.warn("Could not resolve type for {}", variable);
        type = new de.fraunhofer.aisec.cpg.graph.Type(variable.getType().asString());
      } else {
        type = new Type(t, Type.Origin.GUESSED);
      }
    }
    de.fraunhofer.aisec.cpg.graph.FieldDeclaration fieldDeclaration =
        NodeBuilder.newFieldDeclaration(
            variable.getName().asString(),
            type,
            modifiers,
            variable.toString(),
            region,
            initializer);
    lang.getScopeManager().addValueDeclaration(fieldDeclaration);

    return new FieldDeclaration();
  }

  public Declaration /* TODO refine return type*/ handleInitializerDeclaration(
      InitializerDeclaration initializerDecl) {
    return new Declaration();
  }

  public EnumDeclaration handleEnumDeclaration(
      com.github.javaparser.ast.body.EnumDeclaration enumDecl) {
    String name = getAbsoluteName(enumDecl.getNameAsString());
    Region region = getRegion(enumDecl);

    de.fraunhofer.aisec.cpg.graph.EnumDeclaration enumDeclaration =
        NodeBuilder.newEnumDeclaration(name, enumDecl.toString(), region);
    List<EnumConstantDeclaration> entries =
        enumDecl.getEntries().stream()
            .map(e -> (EnumConstantDeclaration) handle(e))
            .collect(Collectors.toList());
    entries.forEach(e -> e.setType(new Type(enumDeclaration.getName())));
    enumDeclaration.setEntries(entries);

    List<Type> superTypes =
        enumDecl.getImplementedTypes().stream()
            .map(this.lang::getTypeAsGoodAsPossible)
            .collect(Collectors.toList());
    enumDeclaration.setSuperTypes(superTypes);

    return enumDeclaration;
  }

  /* Not so sure about the place of Annotations in the CPG currently */

  public EnumConstantDeclaration handleEnumConstantDeclaration(
      com.github.javaparser.ast.body.EnumConstantDeclaration enumConstDecl) {
    return NodeBuilder.newEnumConstantDeclaration(
        enumConstDecl.getNameAsString(), enumConstDecl.toString(), getRegion(enumConstDecl));
  }

  public Declaration /* TODO refine return type*/ handleAnnotationDeclaration(
      AnnotationDeclaration annotationConstDecl) {
    return new Declaration();
  }

  public Declaration /* TODO refine return type*/ handleAnnotationMemberDeclaration(
      AnnotationMemberDeclaration annotationMemberDecl) {
    return new Declaration();
  }

  private Region getRegion(NodeWithRange<?> node) {
    Optional<Position> begin = node.getBegin();
    Optional<Position> end = node.getEnd();
    Position unknownPosition = Position.pos(-1, -1);
    return new Region(
        begin.orElse(unknownPosition).line,
        begin.orElse(unknownPosition).column,
        end.orElse(unknownPosition).line,
        end.orElse(unknownPosition).column);
  }

  private String getAbsoluteName(String name) {
    String prefix = lang.getScopeManager().getCurrentNamePrefix();
    name =
        (prefix != null && prefix.length() > 0 ? prefix + lang.getNamespaceDelimiter() : "") + name;
    return name;
  }
}
