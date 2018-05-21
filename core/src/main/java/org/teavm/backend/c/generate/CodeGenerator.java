/*
 *  Copyright 2018 Alexey Andreev.
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
 */
package org.teavm.backend.c.generate;

import java.util.Set;
import org.teavm.ast.AsyncMethodNode;
import org.teavm.ast.AsyncMethodPart;
import org.teavm.ast.MethodNode;
import org.teavm.ast.RegularMethodNode;
import org.teavm.ast.VariableNode;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;
import org.teavm.model.util.VariableType;
import org.teavm.runtime.Fiber;

public class CodeGenerator {
    private GenerationContext context;
    private CodeWriter writer;
    private CodeWriter localsWriter;
    private NameProvider names;
    private Set<? super String> includes;

    public CodeGenerator(GenerationContext context, CodeWriter writer, Set<? super String> includes) {
        this.context = context;
        this.writer = writer;
        this.names = context.getNames();
        this.includes = includes;
    }

    public void generateMethod(MethodNode methodNode) {
        generateMethodSignature(writer, methodNode.getReference(),
                methodNode.getModifiers().contains(ElementModifier.STATIC), true);

        writer.print(" {").indent().println();

        localsWriter = writer.fragment();
        CodeGenerationVisitor visitor = generateMethodBody(methodNode);
        generateLocals(methodNode, visitor.getTemporaries());

        writer.outdent().println("}");
    }

    public CodeGenerationVisitor generateMethodBody(MethodNode methodNode) {
        if (methodNode instanceof RegularMethodNode) {
            return generateMethodBody((RegularMethodNode) methodNode);
        } else if (methodNode instanceof AsyncMethodNode) {
            return generateMethodBody((AsyncMethodNode) methodNode);
        } else {
            throw new IllegalArgumentException();
        }
    }

    private CodeGenerationVisitor generateMethodBody(RegularMethodNode methodNode) {
        CodeGenerationVisitor visitor = new CodeGenerationVisitor(context, writer, includes);
        visitor.setCallingMethod(methodNode.getReference());
        methodNode.getBody().acceptVisitor(visitor);
        return visitor;
    }

    private CodeGenerationVisitor generateMethodBody(AsyncMethodNode methodNode) {
        CodeGenerationVisitor visitor = new CodeGenerationVisitor(context, writer, includes);
        visitor.setCallingMethod(methodNode.getReference());

        String currentFiberName = names.forMethod(new MethodReference(Fiber.class, "current", Fiber.class));
        localsWriter.println("int32_t ptr = 0;");
        localsWriter.println("void* fiber = " + currentFiberName + "();");

        generateRestoreState(methodNode);
        renderAsyncPrologue();
        renderStateMachine(methodNode, visitor);
        renderAsyncEpilogue();
        generateSaveState(methodNode);

        return visitor;
    }

    private void renderStateMachine(AsyncMethodNode methodNode, CodeGenerationVisitor visitor) {
        for (int i = 0; i < methodNode.getBody().size(); ++i) {
            writer.println("case " + i + ":;").indent();
            AsyncMethodPart part = methodNode.getBody().get(i);
            visitor.setCurrentPart(i);
            visitor.setEnd(true);
            part.getStatement().acceptVisitor(visitor);
            writer.outdent();
        }
    }

    private void generateRestoreState(AsyncMethodNode methodNode) {
        int minVar = methodNode.getReference().parameterCount() + 1;

        String isResumingName = names.forMethod(new MethodReference(Fiber.class, "isResuming", boolean.class));
        writer.println("if (" + isResumingName + "(fiber)) {").indent();
        for (int i = methodNode.getVariables().size() - 1; i >= 0; --i) {
            VariableNode variable = methodNode.getVariables().get(i);
            if (variable.getIndex() < minVar) {
                continue;
            }
            generateRestoreVariable("local_" + variable.getIndex(), variable.getType());
        }
        generateRestoreVariable("ptr", VariableType.INT);
        writer.outdent().println("}");
    }

    private void generateSaveState(AsyncMethodNode methodNode) {
        int minVar = methodNode.getReference().parameterCount() + 1;

        generateSaveVariable("ptr", VariableType.INT);
        for (int i = 0; i < methodNode.getVariables().size(); ++i) {
            VariableNode variable = methodNode.getVariables().get(i);
            if (variable.getIndex() < minVar) {
                continue;
            }
            generateSaveVariable("local_" + variable.getIndex(), variable.getType());
        }
    }

    private void generateRestoreVariable(String name, VariableType type) {
        MethodReference method;
        switch (type) {
            case INT:
                method = new MethodReference(Fiber.class, "popInt", int.class);
                break;
            case LONG:
                method = new MethodReference(Fiber.class, "popLong", long.class);
                break;
            case FLOAT:
                method = new MethodReference(Fiber.class, "popFloat", float.class);
                break;
            case DOUBLE:
                method = new MethodReference(Fiber.class, "popDouble", double.class);
                break;
            default:
                method = new MethodReference(Fiber.class, "popObject", Object.class);
                break;
        }
        writer.println(name + " = " + names.forMethod(method) + "(fiber);");
    }


    private void generateSaveVariable(String name, VariableType type) {
        MethodReference method;
        switch (type) {
            case INT:
                method = new MethodReference(Fiber.class, "push", int.class, void.class);
                break;
            case LONG:
                method = new MethodReference(Fiber.class, "push", long.class, void.class);
                break;
            case FLOAT:
                method = new MethodReference(Fiber.class, "push", float.class, void.class);
                break;
            case DOUBLE:
                method = new MethodReference(Fiber.class, "push", double.class, void.class);
                break;
            default:
                method = new MethodReference(Fiber.class, "push", Object.class, void.class);
                break;
        }
        writer.println(names.forMethod(method) + "(fiber, " + name + ");");
    }

    private void renderAsyncPrologue() {
        writer.println("while (1) {").indent();
        writer.println("switch (ptr) {").indent();
    }

    private void renderAsyncEpilogue() {
        writer.outdent().println("}");
        writer.println("next_state:;");
        writer.outdent().println("}");
        writer.println("exit_loop:");
    }

    public void generateMethodSignature(CodeWriter writer, MethodReference methodRef, boolean isStatic,
            boolean withNames) {
        writer.print("static ");
        writer.printType(methodRef.getReturnType()).print(" ").print(names.forMethod(methodRef)).print("(");

        generateMethodParameters(writer, methodRef.getDescriptor(), isStatic, withNames);

        writer.print(")");
    }

    public void generateMethodParameters(CodeWriter writer, MethodDescriptor methodRef, boolean isStatic,
            boolean withNames) {
        if (methodRef.parameterCount() == 0 && isStatic) {
            return;
        }

        int start = 0;
        if (!isStatic) {
            writer.print("void*");
            if (withNames) {
                writer.print(" _this_");
            }
        } else {
            writer.printType(methodRef.parameterType(0));
            if (withNames) {
                writer.print(" local_1");
            }
            start++;
        }

        for (int i = start; i < methodRef.parameterCount(); ++i) {
            writer.print(", ").printType(methodRef.parameterType(i));
            if (withNames) {
                writer.print(" ").print("local_").print(String.valueOf(i + 1));
            }
        }
    }

    private void generateLocals(MethodNode methodNode, int[] temporaryCount) {
        int start = methodNode.getReference().parameterCount() + 1;
        for (int i = start; i < methodNode.getVariables().size(); ++i) {
            VariableNode variableNode = methodNode.getVariables().get(i);
            if (variableNode.getType() == null) {
                continue;
            }
            localsWriter.printType(variableNode.getType()).print(" local_").print(String.valueOf(i)).println(";");
        }

        for (CVariableType type : CVariableType.values()) {
            for (int i = 0; i < temporaryCount[type.ordinal()]; ++i) {
                localsWriter.print(type.text + " tmp_" + type.name().toLowerCase() + "_" + i).println(";");
            }
        }
    }
}
