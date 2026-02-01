package org.laykon.parser;

import org.laykon.model.ModelRepository;

import java.nio.file.Path;
import java.util.Set;

public interface LanguageParser {

    Set<String> extensions();

    void parse(Path file, ModelRepository repo) throws Exception;
}
