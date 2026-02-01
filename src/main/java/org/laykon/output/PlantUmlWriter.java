package org.laykon.output;

import org.laykon.model.*;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlantUmlWriter {

    public static void write(Path output, ModelRepository repo) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            w.write("@startuml\n\n");

            for (TypeModel t : repo.all()) {
                w.write((t.kind == TypeKind.INTERFACE ? "interface " : "class ") + t.name + "\n");
            }

            w.write("\n");

            for (TypeModel t : repo.all()) {
                for (String e : t.extendsTypes) {
                    w.write(e + " <|-- " + t.name + "\n");
                }
                for (String i : t.implementsTypes) {
                    w.write(i + " <|.. " + t.name + "\n");
                }
            }

            w.write("\n");

            for (TypeModel t : repo.all()) {
                for (String d : t.dependencies) {
                    if (repo.contains(d)) {
                        w.write(t.name + " ..> " + d + "\n");
                    }
                }
            }

            w.write("\n@enduml\n");
        }
    }
}
