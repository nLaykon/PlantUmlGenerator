package org.laykon;

import org.laykon.model.ModelRepository;
import org.laykon.output.PlantUmlWriter;
import org.laykon.parser.*;

import java.nio.file.*;
import java.util.List;

public class Main {

    private static final List<LanguageParser> PARSERS = List.of(
            new JavaLanguageParser()
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: <srcDir> <output.puml>");
            return;
        }

        Path srcRoot = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        ModelRepository repo = new ModelRepository();

        Files.walk(srcRoot)
                .filter(Files::isRegularFile)
                .forEach(p -> PARSERS.forEach(parser -> {
                    if (parser.extensions().contains(ext(p))) {
                        try {
                            parser.parse(p, repo);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }));

        PlantUmlWriter.write(output, repo);
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i == -1 ? "" : n.substring(i + 1);
    }
}
