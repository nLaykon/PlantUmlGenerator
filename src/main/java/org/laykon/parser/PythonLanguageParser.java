package org.laykon.parser;

import org.laykon.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class PythonLanguageParser implements LanguageParser {

    @Override
    public Set<String> extensions() {
        return Set.of("py");
    }

    @Override
    public void parse(Path file, ModelRepository repo) {
        try {
            List<String> lines = Files.readAllLines(file);

            TypeModel currentClass = null;
            boolean inInit = false;

            for (String line : lines) {
                String trimmed = line.strip();

                if (trimmed.startsWith("class ")) {
                    String name = trimmed.split("\\s+")[1].split("\\(")[0];
                    currentClass = repo.getOrCreate(name, TypeKind.CLASS);
                    inInit = false;

                    if (trimmed.contains("(") && trimmed.contains(")")) {
                        String parent = trimmed.split("\\(")[1].split("\\)")[0];
                        if (!parent.isBlank()) {
                            currentClass.extendsTypes.add(parent);
                        }
                    }

                } else if (trimmed.startsWith("def ") && currentClass != null) {
                    String methodName = trimmed.split("\\s+")[1].split("\\(")[0];

                    inInit = methodName.equals("__init__");

                    currentClass.methods.add(new MethodModel(methodName, "void", List.of()));

                } else if (inInit && trimmed.contains("self.") && currentClass != null) {
                    String name = trimmed.split("=")[0].trim();
                    if (name.startsWith("self.")) {
                        name = name.substring(5);
                        currentClass.fields.add(new FieldModel(name, "any"));
                    }
                }

                if (currentClass != null) {
                    for (String typeName : repo.all().stream().map(t -> t.name).toList()) {
                        if (trimmed.contains(typeName) && !typeName.equals(currentClass.name)) {
                            currentClass.dependencies.add(typeName);
                        }
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
