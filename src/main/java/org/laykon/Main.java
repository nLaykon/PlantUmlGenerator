package org.laykon;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static final Map<String, ClassInfo> classes = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: java PlantUmlGenerator <srcDir> <output.puml>");
            return;
        }

        Path srcRoot = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        Files.walk(srcRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(Main::parseFile);

        writePlantUml(output);
    }

    private static void parseFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String name = clazz.getNameAsString();
                ClassInfo info = classes.computeIfAbsent(name, ClassInfo::new);

                clazz.getExtendedTypes()
                        .forEach(e -> info.extendsTypes.add(e.getNameAsString()));

                clazz.getImplementedTypes()
                        .forEach(i -> info.implementsTypes.add(i.getNameAsString()));

                clazz.getFields().forEach(f ->
                        f.getVariables().forEach(v ->
                                info.fields.add(v.getType() + " " + v.getName())
                        )
                );

                clazz.getMethods().forEach(m -> {
                    String params = m.getParameters().toString();
                    info.methods.add(m.getType() + " " + m.getName() + params);
                });

                clazz.findAll(Type.class).forEach(t -> {
                    String used = t.asString();
                    if (!used.equals(name)) {
                        info.usesTypes.add(used);
                    }
                });
            });

        } catch (Exception ignored) {}
    }

    private static void writePlantUml(Path output) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            w.write("@startuml\n\n");

            for (ClassInfo c : classes.values()) {
                w.write("class " + c.name + " {\n");
                for (String f : c.fields) w.write("  " + f + "\n");
                for (String m : c.methods) w.write("  " + m + "\n");
                w.write("}\n\n");
            }

            for (ClassInfo c : classes.values()) {
                c.extendsTypes.forEach(e ->
                        {
                            try {
                                w.write(e + " <|-- " + c.name + "\n");
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                );
                c.implementsTypes.forEach(i ->
                        {
                            try {
                                w.write(i + " <|.. " + c.name + "\n");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
                c.usesTypes.forEach(u -> {
                    if (classes.containsKey(u)) {
                        try {
                            w.write(c.name + " ..> " + u + "\n");
                        } catch (IOException ignored) {}
                    }
                });
            }

            w.write("\n@enduml\n");
        }
    }

    private static class ClassInfo {
        String name;
        List<String> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        Set<String> extendsTypes = new HashSet<>();
        Set<String> implementsTypes = new HashSet<>();
        Set<String> usesTypes = new HashSet<>();

        ClassInfo(String name) {
            this.name = name;
        }
    }
}
