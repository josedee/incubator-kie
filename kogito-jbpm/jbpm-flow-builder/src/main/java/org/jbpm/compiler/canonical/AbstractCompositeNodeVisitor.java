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
package org.jbpm.compiler.canonical;

import java.util.Optional;

import org.jbpm.compiler.canonical.node.NodeVisitorBuilderService;
import org.jbpm.process.core.ContextContainer;
import org.jbpm.process.core.context.exception.ActionExceptionHandler;
import org.jbpm.process.core.context.exception.ExceptionScope;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.process.instance.impl.actions.SignalProcessInstanceAction;
import org.jbpm.ruleflow.core.RuleFlowProcessFactory;
import org.jbpm.workflow.core.node.CompositeContextNode;
import org.kie.api.definition.process.Node;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.VoidType;

public abstract class AbstractCompositeNodeVisitor<T extends CompositeContextNode> extends AbstractNodeVisitor<T> {

    protected NodeVisitorBuilderService nodevisitorService;

    public AbstractCompositeNodeVisitor(ClassLoader classLoader) {
        super(classLoader);
        this.nodevisitorService = new NodeVisitorBuilderService(classLoader);
    }

    /**
     * Returns the raw factory class whose instances are used as the parent variable
     * in child-node helper methods (e.g. {@code ForEachNodeFactory}, {@code CompositeContextNodeFactory}).
     * Subclasses that declare a {@code factoryClass()} method should override this to return the same value.
     * The default returns {@code null}, which falls back to inlining child nodes without extraction.
     */
    protected Class<?> childFactoryClass() {
        return null;
    }

    /**
     * Visits child nodes of a composite node. Each child's statements are extracted
     * into a dedicated private helper method on the generated XxxProcess class so that
     * no single method can approach the JVM 64 KB bytecode limit, regardless of how
     * many children the composite node contains.
     *
     * <p>
     * If {@link #childFactoryClass()} returns {@code null} (e.g. for composite types
     * that do not expose a known factory class), children are emitted inline as before.
     *
     * @param factoryField the local-variable name of the parent composite node factory
     * @param nodes the child nodes to visit
     * @param body the parent node's method body — receives only the helper call statements
     * @param variableScope the variable scope visible to child nodes
     * @param metadata accumulates the extracted helper methods
     */
    protected <U extends Node> void visitNodes(String factoryField, U[] nodes, BlockStmt body, VariableScope variableScope, ProcessMetaData metadata) {
        Class<?> parentFactoryClass = childFactoryClass();

        for (U node : nodes) {
            AbstractNodeVisitor<U> visitor = (AbstractNodeVisitor<U>) nodevisitorService.findNodeVisitor(node.getClass());
            if (visitor == null) {
                continue;
            }

            if (parentFactoryClass == null) {
                // Fallback: emit inline (pre-existing behaviour for unknown factory types).
                visitor.visitNodeEntryPoint(factoryField, node, body, variableScope, metadata);
                continue;
            }

            // Extract this child's statements into a dedicated private helper method.
            // The helper receives the parent composite factory as its single parameter
            // so that calls like `forEachNode_X.humanTaskNode(...)` remain valid.
            BlockStmt childBody = new BlockStmt();
            visitor.visitNodeEntryPoint(factoryField, node, childBody, variableScope, metadata);

            // Emit exception scope for this child node into its own helper body,
            // so that the generated variable reference (e.g. compositeContextNode_30B57501...)
            // is in scope. Previously this was emitted by ProcessVisitor.visitSubExceptionScope()
            // into the *parent* body, where the variable was not declared.
            if (node instanceof ContextContainer) {
                Object exceptionScope = ((ContextContainer) node).getDefaultContext(ExceptionScope.EXCEPTION_SCOPE);
                if (exceptionScope instanceof ExceptionScope) {
                    String nodeId = visitor.getNodeId(node);
                    ((ExceptionScope) exceptionScope).getExceptionHandlers().forEach((faultCode, exHandler) -> {
                        ActionExceptionHandler handler = (ActionExceptionHandler) exHandler;
                        Optional<String> faultVariable = Optional.ofNullable(handler.getFaultVariable());
                        SignalProcessInstanceAction action = (SignalProcessInstanceAction) handler.getAction().getMetaData("Action");
                        childBody.addStatement(getFactoryMethod(nodeId,
                                RuleFlowProcessFactory.METHOD_ERROR_EXCEPTION_HANDLER,
                                new StringLiteralExpr(action.getSignalName()),
                                faultCode != null ? new StringLiteralExpr(faultCode) : new NullLiteralExpr(),
                                faultVariable.<Expression> map(StringLiteralExpr::new).orElse(new NullLiteralExpr())));
                    });
                }
            }

            String nodeKey = visitor.getNodeKey();
            String helperName = "build" + Character.toUpperCase(nodeKey.charAt(0)) + nodeKey.substring(1)
                    + node.getId().toSanitizeString();

            MethodDeclaration helper = new MethodDeclaration()
                    .setModifiers(Modifier.Keyword.PRIVATE)
                    .setType(new VoidType())
                    .setName(helperName)
                    .addParameter(new Parameter(
                            new ClassOrInterfaceType(null, parentFactoryClass.getSimpleName()),
                            factoryField))
                    .setBody(childBody);

            metadata.addProcessHelperMethod(helper, parentFactoryClass);

            // The parent body only gets the one-line call to the helper.
            body.addStatement(new MethodCallExpr(null, helperName)
                    .addArgument(new NameExpr(factoryField)));
        }
    }

    protected String stripExpression(String expression) {
        if (expression.startsWith("#{")) {
            return expression.substring(2, expression.length() - 1);
        }
        return expression;
    }

}
