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

package de.fraunhofer.aisec.cpg.passes;

import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend;
import de.fraunhofer.aisec.cpg.graph.Declaration;
import de.fraunhofer.aisec.cpg.graph.DeclaredReferenceExpression;
import de.fraunhofer.aisec.cpg.graph.EnumDeclaration;
import de.fraunhofer.aisec.cpg.graph.FieldDeclaration;
import de.fraunhofer.aisec.cpg.graph.HasType;
import de.fraunhofer.aisec.cpg.graph.MemberExpression;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.NodeBuilder;
import de.fraunhofer.aisec.cpg.graph.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.Region;
import de.fraunhofer.aisec.cpg.graph.StaticReferenceExpression;
import de.fraunhofer.aisec.cpg.graph.TranslationUnitDeclaration;
import de.fraunhofer.aisec.cpg.graph.Type;
import de.fraunhofer.aisec.cpg.graph.TypeManager;
import de.fraunhofer.aisec.cpg.graph.ValueDeclaration;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker.ScopedWalker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates new connections between the place where a variable is declared and where it is used.
 *
 * <p>A field access is modeled with a {@link MemberExpression}. After AST building, its base and
 * member references are set to {@link DeclaredReferenceExpression} stubs. This pass resolves those
 * references and makes the member point to the appropriate {@link FieldDeclaration} and the base to
 * the "this" {@link FieldDeclaration} of the containing class. It is also capable of resolving
 * references to fields that are inherited from a superclass and thus not declared in the actual
 * base class. When base or member declarations are not found in the graph, a new "dummy" {@link
 * FieldDeclaration} is being created that is then used to collect all usages to the same unknown
 * declaration. {@link DeclaredReferenceExpression} stubs are removed from the graph after being
 * resolved.
 *
 * <p>A local variable access is modeled directly with a {@link DeclaredReferenceExpression}. This
 * step of the pass doesn't remove the {@link DeclaredReferenceExpression} nodes like in the field
 * usage case but rather makes their "refersTo" point to the appropriate {@link ValueDeclaration}.
 *
 * @author samuel
 */
public class VariableUsageResolver implements Pass {

  private static final Logger log = LoggerFactory.getLogger(VariableUsageResolver.class);
  private Map<Type, List<Type>> superTypesMap = new HashMap<>();
  private Map<Type, RecordDeclaration> recordMap = new HashMap<>();
  private Map<Type, EnumDeclaration> enumMap = new HashMap<>();
  private ScopedWalker walker;

  @Override
  public void cleanup() {
    this.superTypesMap.clear();
    this.superTypesMap = null;
    if (this.recordMap != null) {
      this.recordMap.clear();
      this.recordMap = null;
    }
    this.enumMap.clear();
    this.enumMap = null;
    this.walker = null;
  }

  @Override
  public LanguageFrontend getLang() {
    return null;
  }

  @Override
  public void setLang(LanguageFrontend lang) {}

  @Override
  public void accept(TranslationResult result) {
    walker = new ScopedWalker();

    for (TranslationUnitDeclaration tu : result.getTranslationUnits()) {
      walker.clearCallbacks();
      walker.registerHandler(
          (currClass, currScope, currNode) -> walker.collectDeclarations(tu, currNode));
      walker.registerHandler(this::findRecordsAndEnums);
      walker.iterate(tu);
    }

    Map<Type, List<Type>> currSuperTypes =
        recordMap.values().stream()
            .collect(
                Collectors.toMap(r -> new Type(r.getName()), RecordDeclaration::getSuperTypes));
    superTypesMap.putAll(currSuperTypes);

    for (TranslationUnitDeclaration tu : result.getTranslationUnits()) {
      walker.clearCallbacks();
      walker.registerHandler(this::resolveFieldUsages);
      walker.registerHandler(this::resolveLocalVarUsage);
      walker.iterate(tu);
    }
  }

  private void findRecordsAndEnums(Node node) {
    if (node instanceof RecordDeclaration) {
      Type type = new Type(node.getName());
      recordMap.putIfAbsent(type, (RecordDeclaration) node);
    } else if (node instanceof EnumDeclaration) {
      Type type = new Type(node.getName());
      enumMap.putIfAbsent(type, (EnumDeclaration) node);
    }
  }

  private void resolveLocalVarUsage(Type currentClass, Node currentScope, Node current) {
    if (current instanceof DeclaredReferenceExpression) {
      List<ValueDeclaration> currDeclarations = walker.getDeclarationsForScope(currentScope);
      DeclaredReferenceExpression ref = (DeclaredReferenceExpression) current;
      Optional<? extends ValueDeclaration> refersTo =
          currDeclarations.stream().filter(v -> v.getName().equals(ref.getName())).findFirst();

      // only add new nodes for non-static unknown
      if (!(current instanceof StaticReferenceExpression)
          && refersTo.isEmpty()
          && currentClass != null
          && recordMap.containsKey(currentClass)) {
        // Maybe we are referring to a field instead of a local var
        refersTo = Optional.of(resolveMember(currentClass, (DeclaredReferenceExpression) current));
      }

      ref.setRefersTo(refersTo.orElse(null));
    }
  }

  private void resolveFieldUsages(Node current) {
    if (current instanceof MemberExpression) {
      MemberExpression memberExpression = (MemberExpression) current;
      Node base = memberExpression.getBase();
      Node member = memberExpression.getMember();
      if (base instanceof DeclaredReferenceExpression) {
        base = resolveBase((DeclaredReferenceExpression) memberExpression.getBase());
      }
      if (member instanceof DeclaredReferenceExpression) {
        if (base instanceof EnumDeclaration) {
          String name = member.getName();
          member =
              ((EnumDeclaration) base)
                  .getEntries().stream()
                      .filter(e -> e.getName().equals(name))
                      .findFirst()
                      .orElse(null);
        } else {
          Type baseType = new Type(Type.UNKNOWN_TYPE);
          if (base instanceof HasType) {
            baseType = ((HasType) base).getType();
          }
          member =
              base == null
                  ? null
                  : resolveMember(
                      baseType, (DeclaredReferenceExpression) memberExpression.getMember());
          if (member != null) {
            HasType typedMember = (HasType) member;
            typedMember.setType(memberExpression.getType());
            Set<Type> subTypes = new HashSet<>(typedMember.getPossibleSubTypes());
            subTypes.addAll(memberExpression.getPossibleSubTypes());
            typedMember.setPossibleSubTypes(subTypes);
          }
        }
      }
      memberExpression.setBase(base);
      memberExpression.setMember(member);
    }
  }

  private Declaration resolveBase(DeclaredReferenceExpression reference) {
    // check if this refers to an enum
    if (enumMap.containsKey(reference.getType())) {
      return enumMap.get(reference.getType());
    }

    // check if we have this type as a class in our graph. If so, we can refer to its "this"
    // field
    if (recordMap.containsKey(reference.getType())) {
      return recordMap.get(reference.getType()).getThis();
    } else {
      log.info(
          "Type declaration for {} not found in graph, using dummy to collect all " + "usages",
          reference.getType());
      return handleUnknownDeclaration(reference.getType(), reference);
    }
  }

  private ValueDeclaration resolveMember(
      Type containingClass, DeclaredReferenceExpression reference) {
    Optional<FieldDeclaration> member =
        TypeManager.getInstance().isUnknown(containingClass)
            ? Optional.empty()
            : recordMap.get(containingClass).getFields().stream()
                .filter(f -> f.getName().equals(reference.getName()))
                .findFirst();
    if (member.isEmpty()) {
      member =
          superTypesMap.getOrDefault(containingClass, Collections.emptyList()).stream()
              .map(recordMap::get)
              .filter(Objects::nonNull)
              .flatMap(r -> r.getFields().stream())
              .filter(f -> f.getName().equals(reference.getName()))
              .findFirst();
    }
    // Attention: using orElse instead of orElseGet will always invoke unknown declaration handling!
    return member.orElseGet(() -> handleUnknownDeclaration(containingClass, reference));
  }

  private FieldDeclaration handleUnknownDeclaration(
      Type base, DeclaredReferenceExpression reference) {
    recordMap.putIfAbsent(
        base,
        NodeBuilder.newRecordDeclaration(
            base.getTypeName(), new ArrayList<>(), Type.UNKNOWN_TYPE, Type.UNKNOWN_TYPE));
    // fields.putIfAbsent(base, new ArrayList<>());
    List<FieldDeclaration> declarations = recordMap.get(base).getFields();
    Optional<FieldDeclaration> target =
        declarations.stream().filter(f -> f.getName().equals(reference.getName())).findFirst();
    if (target.isEmpty()) {
      FieldDeclaration declaration =
          NodeBuilder.newFieldDeclaration(
              reference.getName(),
              reference.getType(),
              Collections.emptyList(),
              "",
              new Region(),
              null);
      declarations.add(declaration);
      declaration.setDummy(true);
      // lang.getScopeManager().addValueDeclaration(declaration);
      return declaration;
    } else {
      return target.get();
    }
  }
}
