package org.laykon.parser;

import org.laykon.model.*;
import org.laykon.util.Debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
            int classIndent = -1;
            String currentMethod = null;
            int methodIndent = -1;
            boolean inInit = false;
            Map<String, String> initParamTypes = new HashMap<>();

            Debug.log("Py parse start: " + file + " (" + lines.size() + " lines)");
            for (String line : lines) {
                int indent = countIndent(line);
                String trimmed = line.strip();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                if (currentClass != null && indent <= classIndent && !trimmed.startsWith("@")) {
                    currentClass = null;
                    classIndent = -1;
                    currentMethod = null;
                    methodIndent = -1;
                    inInit = false;
                    initParamTypes.clear();
                }

                if (currentMethod != null && indent <= methodIndent) {
                    currentMethod = null;
                    methodIndent = -1;
                    inInit = false;
                    initParamTypes.clear();
                }

                if (trimmed.startsWith("class ")) {
                    String name = parseClassName(trimmed);
                    List<String> parents = parseBaseClasses(trimmed);
                    TypeKind kind = TypeKind.CLASS;
                    if (parents.contains("Enum")) {
                        kind = TypeKind.ENUM;
                    } else if (parents.contains("ABC")) {
                        kind = TypeKind.INTERFACE;
                    }
                    currentClass = repo.getOrCreate(name, kind);
                    classIndent = indent;
                    currentMethod = null;
                    methodIndent = -1;
                    inInit = false;
                    initParamTypes.clear();
                    Debug.log("Py class " + name);

                    for (String parent : parents) {
                        if (!parent.isBlank()) {
                            if ("Enum".equals(parent) || "ABC".equals(parent)) {
                                continue;
                            }
                            currentClass.extendsTypes.add(parent);
                            addDependencies(parent, currentClass);
                        }
                    }

                } else if (trimmed.startsWith("def ") && currentClass != null && indent > classIndent) {
                    String methodName = parseMethodName(trimmed);
                    String paramsPart = parseParams(trimmed);
                    String returnType = parseReturnType(trimmed);
                    List<String> params = parseParamTypes(paramsPart);
                    initParamTypes = parseParamNameTypes(paramsPart);

                    inInit = methodName.equals("__init__");
                    currentMethod = methodName;
                    methodIndent = indent;

                    if (inInit) {
                        returnType = "void";
                    }

                    if (!"__init__".equals(methodName)) {
                        String normalizedReturn = "None".equalsIgnoreCase(returnType) ? "void" : returnType;
                        currentClass.methods.add(new MethodModel(methodName, normalizedReturn, params));
                        addDependencies(normalizedReturn, currentClass);
                        for (String p : params) {
                            addDependencies(p, currentClass);
                        }
                    }

                } else if (currentClass != null && inInit && trimmed.contains("self.") && indent > methodIndent) {
                    FieldModel field = parseSelfAssignment(trimmed, initParamTypes);
                    if (field != null) {
                        currentClass.fields.add(field);
                        addDependencies(field.type, currentClass);
                    }

                } else if (currentClass != null && currentMethod == null && indent > classIndent) {
                    FieldModel field = parseClassField(trimmed, currentClass);
                    if (field != null) {
                        currentClass.fields.add(field);
                        addDependencies(field.type, currentClass);
                    }
                }
            }
            Debug.log("Py parse done: " + file);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int countIndent(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                count++;
            } else if (c == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }

    private String parseClassName(String trimmed) {
        String head = trimmed.substring("class ".length()).trim();
        int paren = head.indexOf('(');
        int colon = head.indexOf(':');
        int end = head.length();
        if (paren >= 0) {
            end = paren;
        } else if (colon >= 0) {
            end = colon;
        }
        return head.substring(0, end).trim();
    }

    private List<String> parseBaseClasses(String trimmed) {
        int open = trimmed.indexOf('(');
        int close = trimmed.indexOf(')');
        if (open < 0 || close < 0 || close <= open) {
            return List.of();
        }
        String inside = trimmed.substring(open + 1, close).trim();
        if (inside.isEmpty()) {
            return List.of();
        }
        String[] parts = inside.split(",");
        List<String> bases = new ArrayList<>();
        for (String part : parts) {
            String base = part.trim();
            if (!base.isEmpty()) {
                bases.add(base);
            }
        }
        return bases;
    }

    private String parseMethodName(String trimmed) {
        String head = trimmed.substring("def ".length()).trim();
        int paren = head.indexOf('(');
        if (paren < 0) {
            return head;
        }
        return head.substring(0, paren).trim();
    }

    private String parseParams(String trimmed) {
        int open = trimmed.indexOf('(');
        int close = trimmed.indexOf(')');
        if (open < 0 || close < 0 || close <= open) {
            return "";
        }
        return trimmed.substring(open + 1, close).trim();
    }

    private String parseReturnType(String trimmed) {
        int arrow = trimmed.indexOf("->");
        if (arrow < 0) {
            return "void";
        }
        int colon = trimmed.indexOf(':', arrow);
        String ret = colon > arrow ? trimmed.substring(arrow + 2, colon) : trimmed.substring(arrow + 2);
        ret = ret.trim();
        return ret.isEmpty() ? "void" : ret;
    }

    private List<String> parseParamTypes(String paramsPart) {
        if (paramsPart.isEmpty()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        String[] params = paramsPart.split(",");
        for (String raw : params) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("self") || p.startsWith("cls")) {
                continue;
            }
            int colon = p.indexOf(':');
            String type;
            if (colon >= 0) {
                type = p.substring(colon + 1).trim();
            } else {
                type = "any";
            }
            int eq = type.indexOf('=');
            if (eq >= 0) {
                type = type.substring(0, eq).trim();
            }
            if (!type.isEmpty()) {
                types.add(type);
            }
        }
        return types;
    }

    private Map<String, String> parseParamNameTypes(String paramsPart) {
        Map<String, String> map = new HashMap<>();
        if (paramsPart.isEmpty()) {
            return map;
        }
        String[] params = paramsPart.split(",");
        for (String raw : params) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("self") || p.startsWith("cls")) {
                continue;
            }
            int colon = p.indexOf(':');
            String name = colon >= 0 ? p.substring(0, colon).trim() : p;
            String type = colon >= 0 ? p.substring(colon + 1).trim() : "any";
            int eq = type.indexOf('=');
            if (eq >= 0) {
                type = type.substring(0, eq).trim();
            }
            if (!name.isEmpty()) {
                map.put(name, type.isEmpty() ? "any" : type);
            }
        }
        return map;
    }

    private FieldModel parseSelfAssignment(String trimmed, Map<String, String> initParamTypes) {
        String line = trimmed;
        int eq = line.indexOf('=');
        String left = eq >= 0 ? line.substring(0, eq).trim() : line.trim();
        if (!left.startsWith("self.")) {
            return null;
        }
        String nameType = left.substring(5).trim();
        String name = nameType;
        String type = "any";
        int colon = nameType.indexOf(':');
        if (colon >= 0) {
            name = nameType.substring(0, colon).trim();
            type = nameType.substring(colon + 1).trim();
        }
        if ("any".equals(type) && eq >= 0) {
            String right = line.substring(eq + 1).trim();
            if (initParamTypes.containsKey(right)) {
                type = initParamTypes.get(right);
            }
        }
        if (name.isEmpty()) {
            return null;
        }
        if (type.isEmpty()) {
            type = "any";
        }
        return new FieldModel(name, type);
    }

    private FieldModel parseClassField(String trimmed, TypeModel currentClass) {
        if (currentClass.kind == TypeKind.ENUM) {
            int eq = trimmed.indexOf('=');
            String name = eq >= 0 ? trimmed.substring(0, eq).trim() : trimmed.trim();
            if (!name.isEmpty() && name.chars().allMatch(c -> Character.isUpperCase(c) || c == '_' || Character.isDigit(c))) {
                return new FieldModel(name, "");
            }
        }

        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String name = trimmed.substring(0, colon).trim();
        if (name.isEmpty()) {
            return null;
        }
        String type = trimmed.substring(colon + 1).trim();
        int eq = type.indexOf('=');
        if (eq >= 0) {
            type = type.substring(0, eq).trim();
        }
        if (type.isEmpty()) {
            type = "any";
        }
        return new FieldModel(name, type);
    }

    private void addDependencies(String typeName, TypeModel currentClass) {
        if (typeName == null || typeName.isBlank() || currentClass == null) {
            return;
        }
        for (String dep : extractTypeNames(typeName)) {
            if (!isPrimitiveType(dep) && !dep.equals(currentClass.name) &&
                    !currentClass.extendsTypes.contains(dep) && !currentClass.implementsTypes.contains(dep)) {
                currentClass.dependencies.add(dep);
            }
        }
    }

    private List<String> extractTypeNames(String typeName) {
        String cleaned = typeName.trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        int bracket = cleaned.indexOf('[');
        if (bracket < 0) {
            return List.of(cleaned);
        }
        String base = cleaned.substring(0, bracket).trim();
        String args = cleaned.substring(bracket + 1, cleaned.lastIndexOf(']')).trim();
        List<String> result = new ArrayList<>();
        if (!base.isEmpty()) {
            result.add(base);
        }
        int depth = 0;
        int last = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String part = args.substring(last, i).trim();
                if (!part.isEmpty()) {
                    result.addAll(extractTypeNames(part));
                }
                last = i + 1;
            }
        }
        String tail = args.substring(last).trim();
        if (!tail.isEmpty()) {
            result.addAll(extractTypeNames(tail));
        }
        return result;
    }

    private boolean isPrimitiveType(String type) {
        String lower = type.toLowerCase();
        return lower.equals("int") || lower.equals("float") || lower.equals("bool") || lower.equals("str") ||
                lower.equals("string") || lower.equals("any") || lower.equals("none") ||
                lower.equals("dict") || lower.equals("list") || lower.equals("set") || lower.equals("tuple");
    }
}
