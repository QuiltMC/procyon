package com.strobel.decompiler.languages.java.ast;

import com.strobel.assembler.metadata.FieldDefinition;
import com.strobel.assembler.metadata.FieldReference;
import com.strobel.assembler.metadata.MemberReference;
import com.strobel.assembler.metadata.MethodDefinition;
import com.strobel.assembler.metadata.ParameterDefinition;
import com.strobel.core.VerifyArgument;
import com.strobel.decompiler.DecompilerContext;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.ast.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AnonymousTypeHelper {
    public static void replaceClosureMembers(
        final DecompilerSettings settings,
        final AnonymousObjectCreationExpression node,
        final List<Expression> originalArguments) {

        final TypeDeclaration root = VerifyArgument.notNull(node, "node").getTypeDeclaration();
        final Map<String, Expression> initializers = new HashMap<>();
        final Map<String, Expression> replacements = new HashMap<>();
        final List<AstNode> initializersToRemove = new ArrayList<>();
        final List<ParameterDefinition> parametersToRemove = new ArrayList<>();

        new PhaseOneVisitor(new DecompilerContext(settings), originalArguments, replacements, initializers, parametersToRemove, initializersToRemove).run(root);
        new PhaseTwoVisitor(new DecompilerContext(settings), replacements, initializers).run(root);

        for (final ParameterDefinition p : parametersToRemove) {
            node.getArguments().remove(originalArguments.get(p.getPosition()));
        }

        for (final AstNode n : initializersToRemove) {
            n.remove();
        }
    }

    private final static class PhaseOneVisitor extends ContextTrackingVisitor<Void> {
        private final Map<String, Expression> _replacements;
        private final List<Expression> _originalArguments;
        private final List<ParameterDefinition> _parametersToRemove;
        private final Map<String, Expression> _initializers;
        private final List<AstNode> _nodesToRemove;

        private boolean _baseConstructorCalled;

        public PhaseOneVisitor(
            final DecompilerContext context,
            final List<Expression> originalArguments,
            final Map<String, Expression> replacements,
            final Map<String, Expression> initializers,
            final List<ParameterDefinition> parametersToRemove,
            final List<AstNode> nodesToRemove) {

            super(context);

            _originalArguments = VerifyArgument.notNull(originalArguments, "originalArguments");
            _replacements = VerifyArgument.notNull(replacements, "replacements");
            _initializers = VerifyArgument.notNull(initializers, "initializers");
            _parametersToRemove = VerifyArgument.notNull(parametersToRemove, "parametersToRemove");
            _nodesToRemove = VerifyArgument.notNull(nodesToRemove, "nodesToRemove");
        }

        @Override
        public Void visitMethodDeclaration(final MethodDeclaration node, final Void _) {
            final boolean wasDone = _baseConstructorCalled;

            _baseConstructorCalled = false;

            try {
                return super.visitMethodDeclaration(node, _);
            }
            finally {
                _baseConstructorCalled = wasDone;
            }
        }

        @Override
        protected Void visitChildren(final AstNode node, final Void _) {
            final MethodDefinition currentMethod = context.getCurrentMethod();

            if (currentMethod != null && !(currentMethod.isConstructor() && currentMethod.isSynthetic())) {
                return null;
            }

            return super.visitChildren(node, _);
        }

        @Override
        public Void visitSuperReferenceExpression(final SuperReferenceExpression node, final Void _) {
            super.visitSuperReferenceExpression(node, _);

            if (context.getCurrentMethod() != null &&
                context.getCurrentMethod().isConstructor() &&
                node.getParent() instanceof InvocationExpression) {

                //
                // We only care about field initializations that occur before the base constructor call.
                //
                _baseConstructorCalled = true;
            }

            return null;
        }

        @Override
        public Void visitAssignmentExpression(final AssignmentExpression node, final Void _) {
            super.visitAssignmentExpression(node, _);

            if (context.getCurrentMethod() == null || !context.getCurrentMethod().isConstructor()) {
                return null;
            }

            final Expression left = node.getLeft();
            final Expression right = node.getRight();

            if (left instanceof MemberReferenceExpression) {
                if (right instanceof IdentifierExpression) {
                    final Variable variable = right.getUserData(Keys.VARIABLE);

                    if (variable == null || !variable.isParameter()) {
                        return null;
                    }

                    final MemberReferenceExpression memberReference = (MemberReferenceExpression) left;
                    final MemberReference member = memberReference.getUserData(Keys.MEMBER_REFERENCE);

                    if (member instanceof FieldReference &&
                        memberReference.getTarget() instanceof ThisReferenceExpression) {

                        final FieldDefinition resolvedField = ((FieldReference) member).resolve();

                        if (resolvedField != null && resolvedField.isSynthetic()) {
                            final ParameterDefinition parameter = variable.getOriginalParameter();
                            final int parameterIndex = parameter.getPosition();

                            if (parameterIndex >= 0 && parameterIndex < _originalArguments.size()) {
                                final Expression argument = _originalArguments.get(parameterIndex);

                                if (argument instanceof ThisReferenceExpression) {
                                    //
                                    // Don't replace outer class references; they will be rewritten later.
                                    //
                                    return null;
                                }

                                _parametersToRemove.add(parameter);
                                _replacements.put(member.getFullName(), argument);
                            }
                        }
                    }
                }
                else if (_baseConstructorCalled && right instanceof MemberReferenceExpression) {
                    final MemberReferenceExpression leftMemberReference = (MemberReferenceExpression) left;
                    final MemberReference leftMember = leftMemberReference.getUserData(Keys.MEMBER_REFERENCE);
                    final MemberReferenceExpression rightMemberReference = (MemberReferenceExpression) right;
                    final MemberReference rightMember = right.getUserData(Keys.MEMBER_REFERENCE);

                    if (rightMember instanceof FieldReference &&
                        rightMemberReference.getTarget() instanceof ThisReferenceExpression) {

                        final FieldDefinition resolvedTargetField = ((FieldReference) leftMember).resolve();
                        final FieldDefinition resolvedSourceField = ((FieldReference) rightMember).resolve();

                        if (resolvedSourceField != null &&
                            resolvedTargetField != null &&
                            resolvedSourceField.isSynthetic() &&
                            !resolvedTargetField.isSynthetic()) {

                            final Expression initializer = _replacements.get(rightMember.getFullName());

                            if (initializer != null) {
                                _initializers.put(resolvedTargetField.getFullName(), initializer);

                                if (node.getParent() instanceof ExpressionStatement) {
                                    _nodesToRemove.add(node.getParent());
                                }
                            }
                        }
                    }
                }
            }

            return null;
        }
    }

    private final static class PhaseTwoVisitor extends ContextTrackingVisitor<Void> {
        private final Map<String, Expression> _replacements;
        private final Map<String, Expression> _initializers;

        protected PhaseTwoVisitor(
            final DecompilerContext context,
            final Map<String, Expression> replacements,
            final Map<String, Expression> initializers) {

            super(context);

            _replacements = VerifyArgument.notNull(replacements, "replacements");
            _initializers = VerifyArgument.notNull(initializers, "initializers");
        }

        @Override
        public Void visitFieldDeclaration(final FieldDeclaration node, final Void data) {
            super.visitFieldDeclaration(node, data);

            final FieldDefinition field = node.getUserData(Keys.FIELD_DEFINITION);

            if (field != null &&
                !_initializers.isEmpty() &&
                node.getVariables().size() == 1 &&
                node.getVariables().firstOrNullObject().getInitializer().isNull()) {

                final Expression initializer = _initializers.get(field.getFullName());

                if (initializer != null) {
                    node.getVariables().firstOrNullObject().setInitializer((Expression) initializer.clone());
                }
            }

            return null;
        }

        @Override
        public Void visitMemberReferenceExpression(final MemberReferenceExpression node, final Void _) {
            super.visitMemberReferenceExpression(node, _);

            if (node.getParent() instanceof AssignmentExpression &&
                node.getRole() == AssignmentExpression.LEFT_ROLE) {

                return null;
            }

            if (node.getTarget() instanceof ThisReferenceExpression) {
                final MemberReference member = node.getUserData(Keys.MEMBER_REFERENCE);

                if (member instanceof FieldReference) {
                    final Expression replacement = _replacements.get(member.getFullName());

                    if (replacement != null) {
                        node.replaceWith(replacement.clone());
                    }
                }
            }

            return null;
        }
    }
}