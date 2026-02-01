package org.laykon.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.Type;
import org.laykon.model.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class JavaLanguageParser implements LanguageParser {

    @Override
    public Set<String> extensions() {
        return Set.of("java");
    }

    @Override
    public void parse(Path file, ModelRepository repo) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file);

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {

            TypeModel type = repo.getOrCreate(
                    clazz.getNameAsString(),
                    clazz.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS
            );

            clazz.getExtendedTypes()
                    .forEach(t -> type.extendsTypes.add(t.getNameAsString()));

            clazz.getImplementedTypes()
                    .forEach(t -> type.implementsTypes.add(t.getNameAsString()));

            for (FieldDeclaration field : clazz.getFields()) {
                field.getVariables().forEach(v ->
                        type.fields.add(new FieldModel(
                                v.getNameAsString(),
                                field.getElementType().asString()
                        ))
                );
            }

            for (MethodDeclaration m : clazz.getMethods()) {
                List<String> params = m.getParameters()
                        .stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.toList());

                type.methods.add(new MethodModel(
                        m.getNameAsString(),
                        m.getType().asString(),
                        params
                ));
            }

            clazz.findAll(Type.class).forEach(t -> {
                String used = t.asString();
                if (!used.equals(type.name)) {
                    type.dependencies.add(used);
                }
            });
        }
    }
}
