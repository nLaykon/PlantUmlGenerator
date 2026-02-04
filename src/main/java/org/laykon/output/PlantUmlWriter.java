package org.laykon.output;

import org.laykon.model.*;
import org.laykon.util.Debug;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlantUmlWriter {

    public static void write(Path output, ModelRepository repo) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            w.write("@startuml\n\n");

            for (TypeModel t : repo.all()) {
                Debug.log("Writing type " + t.name + " (" + t.kind + ")");
                w.write(typeKeyword(t) + " " + t.name);
                if (t.fields.isEmpty() && t.methods.isEmpty()) {
                    w.write("\n");
                    continue;
                }

                w.write(" {\n");
                for (FieldModel f : t.fields) {
                    if (f.type == null || f.type.isBlank()) {
                        Debug.log("  field " + f.name);
                        w.write("  " + f.name + "\n");
                    } else {
                        Debug.log("  field " + f.name + " : " + f.type);
                        w.write("  " + f.name + " : " + f.type + "\n");
                    }
                }
                for (MethodModel m : t.methods) {
                    Debug.log("  method " + m.name + "(" + String.join(", ", m.parameters) + ")" +
                            ("void".equalsIgnoreCase(m.returnType) ? "" : " : " + m.returnType));
                    w.write("  " + m.name + "(" + String.join(", ", m.parameters) + ")");
                    if (!"void".equalsIgnoreCase(m.returnType)) {
                        w.write(" : " + m.returnType);
                    }
                    w.write("\n");
                }
                w.write("}\n");
            }

            w.write("\n");

            for (TypeModel t : repo.all()) {
                for (String e : t.extendsTypes) {
                    Debug.log("Inheritance " + e + " <|-- " + t.name);
                    w.write(e + " <|-- " + t.name + "\n");
                }
                for (String i : t.implementsTypes) {
                    Debug.log("Implements " + i + " <|.. " + t.name);
                    w.write(i + " <|.. " + t.name + "\n");
                }
            }

            w.write("\n");

            for (TypeModel t : repo.all()) {
                for (String d : t.dependencies) {
                    if (repo.contains(d)) {
                        Debug.log("Dependency " + t.name + " ..> " + d);
                        w.write(t.name + " ..> " + d + "\n");
                    }
                }
            }

            w.write("\n@enduml\n");
        }
    }

    private static String typeKeyword(TypeModel t) {
        if (t.kind == TypeKind.INTERFACE) {
            return "interface";
        }
        if (t.kind == TypeKind.STRUCT) {
            return "struct";
        }
        if (t.kind == TypeKind.ENUM) {
            return "enum";
        }
        return "class";
    }
}
