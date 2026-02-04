package org.laykon;

import org.laykon.model.ModelRepository;
import org.laykon.output.PlantUmlWriter;
import org.laykon.parser.*;
import org.laykon.util.Debug;

import java.nio.file.*;
import java.util.List;

public class Main {

    private static final List<LanguageParser> PARSERS = List.of(
            new JavaLanguageParser(),
            new PythonLanguageParser(),
            new CSharpLanguageParser()
    );
    public static void main(String[] args) throws Exception {
        int i = 0;
        if (args.length > 0 && "-d".equals(args[0])) {
            Debug.setEnabled(true);
            i = 1;
        }

        if (args.length < i + 2) {
            System.out.println("Usage: [-d] <srcDir> <output.puml>");
            return;
        }

        Path srcRoot = Paths.get(args[i]);
        Path output = Paths.get(args[i + 1]);
        Debug.log("Debug enabled");
        Debug.log("Source root: " + srcRoot);
        Debug.log("Output file: " + output);

        ModelRepository repo = new ModelRepository();

        Files.walk(srcRoot)
                .filter(Files::isRegularFile)
                .forEach(p -> PARSERS.forEach(parser -> {
                    if (parser.extensions().contains(ext(p))) {
                        try {
                            Debug.log("Parsing " + p + " with " + parser.getClass().getSimpleName());
                            parser.parse(p, repo);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }));

        Debug.log("Writing PlantUML");
        PlantUmlWriter.write(output, repo);
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i == -1 ? "" : n.substring(i + 1);
    }

}
