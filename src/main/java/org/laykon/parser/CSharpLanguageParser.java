package org.laykon.parser;

import org.laykon.model.*;
import org.laykon.util.Debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CSharpLanguageParser implements LanguageParser {
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\s*(?:public|private|protected|internal|abstract|sealed|static|partial)?\\s*" +
                    "(class|interface|struct|record|enum)\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*(?::\\s*([^{]+))?"
    );

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|new|extern|unsafe|partial)\\s*" +
                    "(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|new|extern|unsafe|partial\\s+)*" +
                    "([\\w<>,\\.\\[\\]\\?]+(?:\\s+[\\w<>,\\.\\[\\]\\?]+)*)\\s+(\\w+)\\s*\\(([^)]*)\\)"
    );
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:public|private|protected|internal|static|unsafe|extern)\\s*(\\w+)\\s*\\(([^)]*)\\)"
    );

    private static final Pattern FIELD_PROPERTY_PATTERN = Pattern.compile(
            "\\s*(?:public|private|protected|internal|static|readonly|const|volatile)?\\s*" +
                    "([\\w<>,\\s\\.\\[\\]\\?]++)\\s++(\\w+)\\s*(?:=\\s*[^;]*+)?\\s*;"
    );

    private static final Pattern PROPERTY_PATTERN = Pattern.compile(
            "\\s*(?:public|private|protected|internal|static|virtual|override|abstract)?\\s*" +
                    "([\\w<>,\\s\\.\\[\\]\\?]++)\\s++(\\w+)\\s*\\{[^}]*+\\}\\s*(?:=\\s*[^;]*+)?\\s*;?"
    );
    private static final Set<String> GENERIC_CONTAINERS = Set.of(
            "List", "IList", "ICollection", "IEnumerable", "IReadOnlyList", "IReadOnlyCollection",
            "Dictionary", "IDictionary", "IReadOnlyDictionary", "HashSet", "ISet", "Queue", "Stack",
            "Task", "ValueTask", "Nullable", "Tuple", "Func", "Action"
    );

    @Override
    public Set<String> extensions() {
        return Set.of("cs");
    }

    @Override
    public void parse(Path file, ModelRepository repo) {
        try {
            String content = Files.readString(file);
            Debug.log("C# parse start: " + file + " (" + content.length() + " chars)");
            content = removeComments(content);

            Matcher typeMatcher = CLASS_PATTERN.matcher(content);
            int lastIndex = 0;
            int typeCount = 0;

            while (typeMatcher.find(lastIndex)) {
                String typeKeyword = typeMatcher.group(1);
                String typeName = typeMatcher.group(2);
                String inheritance = typeMatcher.group(3);
                typeCount++;
                Debug.log("C# type " + typeName + " at " + typeMatcher.start() + "-" + typeMatcher.end());

                TypeKind kind = TypeKind.CLASS;
                if ("interface".equals(typeKeyword)) {
                    kind = TypeKind.INTERFACE;
                } else if ("enum".equals(typeKeyword)) {
                    kind = TypeKind.ENUM;
                } else if ("struct".equals(typeKeyword) || "record".equals(typeKeyword)) {
                    kind = TypeKind.STRUCT;
                }

                TypeModel type = repo.getOrCreate(typeName, kind);

                if (inheritance != null && !inheritance.trim().isEmpty()) {
                    String[] parents = inheritance.split(",");
                    for (String parent : parents) {
                        parent = parent.trim();
                        if (!parent.isEmpty()) {
                            if (kind == TypeKind.INTERFACE || parent.startsWith("I") &&
                                    Character.isUpperCase(parent.charAt(1))) {
                                type.extendsTypes.add(parent);
                            } else {
                                if (type.extendsTypes.isEmpty() && kind != TypeKind.INTERFACE) {
                                    type.extendsTypes.add(parent);
                                } else {
                                    type.implementsTypes.add(parent);
                                }
                            }
                        }
                    }
                }

                int bodyStart = findOpeningBrace(content, typeMatcher.end());
                if (bodyStart > 0) {
                    int bodyEnd = findMatchingBrace(content, bodyStart);
                    if (bodyEnd > 0) {
                        String body = content.substring(bodyStart + 1, bodyEnd);
                        Debug.log("C# body for " + typeName + " at " + bodyStart + "-" + bodyEnd +
                                " (" + body.length() + " chars)");
                        if (kind == TypeKind.ENUM) {
                            parseEnumBody(body, type);
                        } else {
                            parseTypeBody(body, type);
                        }
                        lastIndex = bodyEnd;
                        continue;
                    }
                }
                lastIndex = typeMatcher.end();
            }
            Debug.log("C# parse done: " + file + " (types: " + typeCount + ")");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseTypeBody(String body, TypeModel type) {
        Debug.log("C# parse body for " + type.name);
        String bodyWithoutMethodBodies = stripMethodBodies(body);

        Set<String> fieldNames = new HashSet<>();
        Set<String> methodKeys = new HashSet<>();

        Matcher fieldPropMatcher = FIELD_PROPERTY_PATTERN.matcher(bodyWithoutMethodBodies);
        int fieldCount = 0;
        while (fieldPropMatcher.find()) {
            fieldCount++;
            String fieldType = fieldPropMatcher.group(1).trim();
            String fieldName = fieldPropMatcher.group(2).trim();

            if (!fieldName.contains("(") && !fieldType.contains("(") &&
                    !fieldPropMatcher.group(0).contains("{")) {
                type.fields.add(new FieldModel(fieldName, fieldType));
                fieldNames.add(fieldName);

                addDependencies(fieldType, type);
            }
        }
        Debug.log("C# fields/properties found: " + fieldCount);

        Matcher propMatcher = PROPERTY_PATTERN.matcher(bodyWithoutMethodBodies);
        int propCount = 0;
        while (propMatcher.find()) {
            propCount++;
            String propType = propMatcher.group(1).trim();
            String propName = propMatcher.group(2).trim();

            type.fields.add(new FieldModel(propName, propType));
            fieldNames.add(propName);

            addDependencies(propType, type);
        }
        Debug.log("C# properties found: " + propCount);

        Matcher methodMatcher = METHOD_PATTERN.matcher(body);
        int methodCount = 0;
        while (methodMatcher.find()) {
            methodCount++;
            String returnType = cleanReturnType(methodMatcher.group(1).trim());
            String methodName = methodMatcher.group(2).trim();
            String paramsStr = methodMatcher.group(3).trim();
            Debug.log("C# method " + type.name + "." + methodName + "()");

            List<String> params = new ArrayList<>();
            if (!paramsStr.isEmpty()) {
                String[] paramArray = paramsStr.split(",");
                for (String param : paramArray) {
                    param = param.trim();
                    if (!param.isEmpty()) {
                        String paramType = extractParamType(param);
                        if (!paramType.isEmpty()) {
                            params.add(paramType);
                            addDependencies(paramType, type);
                        }
                    }
                }
            }

            if (methodName.equals(type.name)) {
                returnType = "void";
            }
            String methodKey = methodName + "|" + String.join(",", params);
            if (methodKeys.add(methodKey)) {
                type.methods.add(new MethodModel(methodName, returnType, params));
            }

            addDependencies(returnType, type);
        }
        Debug.log("C# methods found: " + methodCount);

        Matcher ctorMatcher = CONSTRUCTOR_PATTERN.matcher(body);
        while (ctorMatcher.find()) {
            String ctorName = ctorMatcher.group(1).trim();
            if (!ctorName.equals(type.name)) {
                continue;
            }
            String paramsStr = ctorMatcher.group(2).trim();
            Debug.log("C# ctor " + type.name + "." + ctorName + "()");

            List<String> params = new ArrayList<>();
            if (!paramsStr.isEmpty()) {
                String[] paramArray = paramsStr.split(",");
                for (String param : paramArray) {
                    param = param.trim();
                    if (!param.isEmpty()) {
                        String paramType = extractParamType(param);
                        if (!paramType.isEmpty()) {
                            params.add(paramType);
                            addDependencies(paramType, type);
                        }
                    }
                }
            }

            String methodKey = ctorName + "|" + String.join(",", params);
            if (methodKeys.add(methodKey)) {
                type.methods.add(new MethodModel(ctorName, "void", params));
            }
        }

        extractMembersFromLines(bodyWithoutMethodBodies, type, fieldNames, methodKeys);
    }

    private void extractMembersFromLines(String body, TypeModel type, Set<String> fieldNames, Set<String> methodKeys) {
        String[] lines = body.split("\\R");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            line = stripAttributes(line);
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("class ") || line.startsWith("interface ") || line.startsWith("struct ")
                    || line.startsWith("enum ") || line.startsWith("record ")) {
                continue;
            }

            if (line.contains("{") && line.contains("}") && line.contains("get")) {
                String head = line.substring(0, line.indexOf('{')).trim();
                String[] typeName = extractTypeAndName(head);
                if (typeName != null) {
                    String propType = typeName[0];
                    String propName = typeName[1];
                    if (fieldNames.add(propName)) {
                        type.fields.add(new FieldModel(propName, propType));
                    }
                    addDependencies(propType, type);
                }
                continue;
            }

            if (line.endsWith(";")) {
                int parenIdx = line.indexOf('(');
                int assignIdx = line.indexOf('=');
                int braceIdx = line.indexOf('{');

                boolean looksLikeField = parenIdx < 0 || (assignIdx >= 0 && assignIdx < parenIdx) ||
                        (braceIdx >= 0 && braceIdx < parenIdx);

                if (looksLikeField) {
                    String head = line.substring(0, line.length() - 1).trim();
                    if (assignIdx >= 0) {
                        head = head.substring(0, assignIdx).trim();
                    }
                    if (braceIdx >= 0) {
                        head = head.substring(0, braceIdx).trim();
                    }
                    String[] typeName = extractTypeAndName(head);
                    if (typeName != null) {
                        String fieldType = typeName[0];
                        String fieldName = typeName[1];
                        if (fieldNames.add(fieldName)) {
                            type.fields.add(new FieldModel(fieldName, fieldType));
                        }
                        addDependencies(fieldType, type);
                    }
                    continue;
                }
            }

            if (line.contains("(") && line.contains(")") && line.endsWith(";")) {
                int open = line.indexOf('(');
                int close = line.lastIndexOf(')');
                int assignIdx = line.indexOf('=');
                if (assignIdx >= 0 && assignIdx < open) {
                    continue;
                }
                if (open > 0 && close > open) {
                    String head = line.substring(0, open).trim();
                    String paramsStr = line.substring(open + 1, close).trim();
                    String[] typeName = extractTypeAndName(head);
                    if (typeName != null) {
                        String methodName = typeName[1];
                        String returnType = cleanReturnType(typeName[0]);
                        List<String> params = new ArrayList<>();
                        if (!paramsStr.isEmpty()) {
                            String[] paramArray = paramsStr.split(",");
                            for (String param : paramArray) {
                                String paramType = extractParamType(param.trim());
                                if (!paramType.isEmpty()) {
                                    params.add(paramType);
                                    addDependencies(paramType, type);
                                }
                            }
                        }
                        if (methodName.equals(type.name)) {
                            returnType = "void";
                        }
                        String methodKey = methodName + "|" + String.join(",", params);
                        if (methodKeys.add(methodKey)) {
                            type.methods.add(new MethodModel(methodName, returnType, params));
                        }
                        addDependencies(returnType, type);
                    }
                }
            }
        }
    }

    private void parseEnumBody(String body, TypeModel type) {
        String cleaned = body.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return;
        }
        String[] entries = cleaned.split(",");
        for (String entry : entries) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            int assignIdx = item.indexOf('=');
            if (assignIdx >= 0) {
                item = item.substring(0, assignIdx).trim();
            }
            item = item.replaceAll("^\\[[^\\]]*\\]\\s*", "");
            if (!item.isEmpty()) {
                type.fields.add(new FieldModel(item, ""));
            }
        }
    }

    private String removeComments(String content) {
        content = content.replaceAll("//.*", "");
        content = content.replaceAll("(?s)/\\*.*?\\*/", "");
        return content;
    }

    private int findOpeningBrace(String content, int start) {
        boolean inString = false;
        boolean verbatim = false;
        char stringChar = '\0';

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);

            if (!inString) {
                if (c == '\'' ) {
                    inString = true;
                    verbatim = false;
                    stringChar = c;
                } else if (c == '"') {
                    inString = true;
                    verbatim = false;
                    stringChar = c;
                } else if (c == '@' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    inString = true;
                    verbatim = true;
                    stringChar = '"';
                    i++;
                } else if (c == '$') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        inString = true;
                        verbatim = false;
                        stringChar = '"';
                        i++;
                    } else if (i + 2 < content.length() &&
                            content.charAt(i + 1) == '@' && content.charAt(i + 2) == '"') {
                        inString = true;
                        verbatim = true;
                        stringChar = '"';
                        i += 2;
                    }
                } else if (c == '{') {
                    return i;
                }
            } else {
                if (verbatim && c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inString = false;
                    }
                } else if (!verbatim && c == stringChar && content.charAt(i - 1) != '\\') {
                    inString = false;
                }
            }
        }
        return -1;
    }

    private int findMatchingBrace(String content, int openIndex) {
        int braceCount = 0;
        boolean inString = false;
        boolean verbatim = false;
        char stringChar = '\0';

        for (int i = openIndex; i < content.length(); i++) {
            char c = content.charAt(i);

            if (!inString) {
                if (c == '\'') {
                    inString = true;
                    verbatim = false;
                    stringChar = c;
                } else if (c == '"') {
                    inString = true;
                    verbatim = false;
                    stringChar = c;
                } else if (c == '@' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    inString = true;
                    verbatim = true;
                    stringChar = '"';
                    i++;
                } else if (c == '$') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        inString = true;
                        verbatim = false;
                        stringChar = '"';
                        i++;
                    } else if (i + 2 < content.length() &&
                            content.charAt(i + 1) == '@' && content.charAt(i + 2) == '"') {
                        inString = true;
                        verbatim = true;
                        stringChar = '"';
                        i += 2;
                    }
                } else if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        return i;
                    }
                }
            } else {
                if (verbatim && c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inString = false;
                    }
                } else if (!verbatim && c == stringChar && content.charAt(i - 1) != '\\') {
                    inString = false;
                }
            }
        }
        return -1;
    }

    private String stripMethodBodies(String body) {
        StringBuilder cleaned = new StringBuilder(body);
        Matcher methodMatcher = METHOD_PATTERN.matcher(cleaned);

        int offset = 0;
        int iterations = 0;
        Debug.log("C# strip bodies start");
        while (methodMatcher.find(offset)) {
            iterations++;
            if (iterations % 200 == 0) {
                Debug.log("C# strip bodies progress: " + iterations + " methods scanned");
            }
            int searchStart = methodMatcher.end();
            int semiColon = cleaned.indexOf(";", searchStart);
            int openBrace = findOpeningBrace(cleaned.toString(), searchStart);
            if (semiColon > 0 && (openBrace < 0 || semiColon < openBrace)) {
                offset = semiColon + 1;
                continue;
            }
            if (openBrace < 0) {
                offset = methodMatcher.end();
                continue;
            }

            int closeBrace = findMatchingBrace(cleaned.toString(), openBrace);
            if (closeBrace < 0) {
                offset = methodMatcher.end();
                continue;
            }
            for (int i = openBrace + 1; i <= closeBrace; i++) {
                cleaned.setCharAt(i, ' ');
            }
            offset = closeBrace + 1;
        }
        Debug.log("C# strip bodies end (methods: " + iterations + ")");

        return cleaned.toString();
    }

    private boolean isPrimitiveType(String type) {
        String lower = type.toLowerCase();
        return lower.matches("^(int|long|short|byte|float|double|decimal|bool|char|string|object|void)$") ||
                lower.matches("^[a-z]\\w*$") && Character.isLowerCase(type.charAt(0));
    }

    private String cleanGenericType(String type) {
        int genericStart = type.indexOf('<');
        if (genericStart > 0) {
            return type.substring(0, genericStart).trim();
        }

        int arrayStart = type.indexOf('[');
        if (arrayStart > 0) {
            return type.substring(0, arrayStart).trim();
        }

        if (type.endsWith("?")) {
            return type.substring(0, type.length() - 1).trim();
        }

        return type.trim();
    }

    private void addDependencies(String typeName, TypeModel type) {
        if (typeName == null || typeName.isBlank()) {
            return;
        }
        for (String dep : extractTypeNames(typeName)) {
            if (!isPrimitiveType(dep) && !dep.equals(type.name)) {
                type.dependencies.add(dep);
            }
        }
    }

    private List<String> extractTypeNames(String typeName) {
        String cleaned = typeName.trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        cleaned = cleaned.replace("?", "");
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
        }

        int genericStart = cleaned.indexOf('<');
        if (genericStart < 0) {
            return List.of(cleaned);
        }

        String base = cleaned.substring(0, genericStart).trim();
        String args = cleaned.substring(genericStart + 1, cleaned.lastIndexOf('>')).trim();
        List<String> result = new ArrayList<>();

        if (!base.isEmpty() && !GENERIC_CONTAINERS.contains(base)) {
            result.add(base);
        }

        int depth = 0;
        int last = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
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

    private String extractParamType(String param) {
        String cleaned = param.replaceAll("^\\[[^\\]]*\\]\\s*", "").trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        if (tokens.length == 0) {
            return "";
        }
        int idx = 0;
        while (idx < tokens.length && isParamModifier(tokens[idx])) {
            idx++;
        }
        if (idx >= tokens.length) {
            return "";
        }
        return tokens[idx].trim();
    }

    private boolean isParamModifier(String token) {
        return "ref".equals(token) || "out".equals(token) || "in".equals(token) || "params".equals(token);
    }

    private String cleanReturnType(String returnType) {
        String cleaned = returnType.trim().replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        int idx = 0;
        while (idx < tokens.length && isModifier(tokens[idx])) {
            idx++;
        }
        if (idx >= tokens.length) {
            return cleaned;
        }
        StringBuilder sb = new StringBuilder(tokens[idx]);
        for (int i = idx + 1; i < tokens.length; i++) {
            if (isModifier(tokens[i])) {
                continue;
            }
            sb.append(" ").append(tokens[i]);
        }
        return sb.toString().trim();
    }

    private boolean isModifier(String token) {
        return "public".equals(token) || "private".equals(token) || "protected".equals(token) ||
                "internal".equals(token) || "static".equals(token) || "virtual".equals(token) ||
                "override".equals(token) || "abstract".equals(token) || "async".equals(token) ||
                "sealed".equals(token) || "new".equals(token) || "extern".equals(token) ||
                "unsafe".equals(token) || "partial".equals(token);
    }

    private String stripAttributes(String line) {
        String trimmed = line.trim();
        while (trimmed.startsWith("[") && trimmed.contains("]")) {
            trimmed = trimmed.substring(trimmed.indexOf(']') + 1).trim();
        }
        return trimmed;
    }

    private String[] extractTypeAndName(String head) {
        String cleaned = head.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        String[] tokens = cleaned.split(" ");
        List<String> parts = new ArrayList<>();
        for (String token : tokens) {
            if (isModifier(token) || isFieldModifier(token)) {
                continue;
            }
            parts.add(token);
        }
        if (parts.size() < 2) {
            return null;
        }
        String name = parts.get(parts.size() - 1);
        String type = String.join(" ", parts.subList(0, parts.size() - 1));
        return new String[]{type, name};
    }

    private boolean isFieldModifier(String token) {
        return "readonly".equals(token) || "const".equals(token) || "volatile".equals(token);
    }
}
