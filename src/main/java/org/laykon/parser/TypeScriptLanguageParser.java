package org.laykon.parser;

import org.laykon.model.*;
import org.laykon.util.Debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeScriptLanguageParser implements LanguageParser {
    private static final Pattern TYPE_HEADER_PATTERN = Pattern.compile(
            "\\b(class|interface|enum)\\s+(\\w+)\\s*([^\\{]*)\\{"
    );
    private static final Pattern TYPE_ALIAS_START_PATTERN = Pattern.compile(
            "\\btype\\s+(\\w+)(?:\\s*<[^>]*>)?\\s*="
    );
    private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\bextends\\s+([^\\{]+?)(?=\\bimplements\\b|$)");
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\bimplements\\s+([^\\{]+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(?:(?:public|private|protected|static|abstract|override|readonly|async)\\s+)*" +
                    "(\\w+)\\s*\\(([^)]*)\\)\\s*(?::\\s*([^\\{;]+))?"
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^(?:(?:public|private|protected|static|readonly|abstract)\\s+)*" +
                    "(\\w+)\\??\\s*:\\s*([^=;]+)"
    );
    private static final Pattern ALIAS_FIELD_PATTERN = Pattern.compile(
            "(\\w+)\\??\\s*:\\s*([^;\\}]+)"
    );
    private static final Set<String> PRIMITIVES = Set.of(
            "string", "number", "boolean", "void", "any", "unknown", "never", "null", "undefined",
            "object", "bigint", "symbol"
    );

    @Override
    public Set<String> extensions() {
        return Set.of("ts");
    }

    @Override
    public void parse(Path file, ModelRepository repo) {
        try {
            String content = Files.readString(file);
            Debug.log("TS parse start: " + file + " (" + content.length() + " chars)");
            content = removeComments(content);

            parseTypeAliases(content, repo);

            Matcher typeMatcher = TYPE_HEADER_PATTERN.matcher(content);
            int lastIndex = 0;
            while (typeMatcher.find(lastIndex)) {
                String kindToken = typeMatcher.group(1);
                String typeName = typeMatcher.group(2);
                String headerRest = typeMatcher.group(3);

                if ("class".equals(kindToken) && "extends".equals(typeName)) {
                    lastIndex = typeMatcher.end();
                    continue;
                }

                TypeKind kind = TypeKind.CLASS;
                if ("interface".equals(kindToken)) {
                    kind = TypeKind.INTERFACE;
                } else if ("enum".equals(kindToken)) {
                    kind = TypeKind.ENUM;
                }

                TypeModel type = repo.getOrCreate(typeName, kind);
                Debug.log("TS type " + typeName + " (" + kindToken + ")");

                parseHeaderRelations(headerRest, type);
                parseGenericConstraints(headerRest, type);

                int bodyStart = content.indexOf('{', typeMatcher.end() - 1);
                if (bodyStart >= 0) {
                    int bodyEnd = findMatchingBrace(content, bodyStart);
                    if (bodyEnd > bodyStart) {
                        String body = content.substring(bodyStart + 1, bodyEnd);
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

            Debug.log("TS parse done: " + file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseHeaderRelations(String headerRest, TypeModel type) {
        String header = stripGenerics(headerRest);
        Matcher extendsMatcher = EXTENDS_PATTERN.matcher(header);
        if (extendsMatcher.find()) {
            String extendPart = extendsMatcher.group(1).trim();
            for (String base : splitTypes(extendPart)) {
                String clean = cleanTypeName(base);
                if (!clean.isEmpty()) {
                    type.extendsTypes.add(clean);
                    addDependencies(clean, type);
                }
            }
        }
        Matcher implementsMatcher = IMPLEMENTS_PATTERN.matcher(header);
        if (implementsMatcher.find()) {
            String implPart = implementsMatcher.group(1).trim();
            for (String impl : splitTypes(implPart)) {
                String clean = cleanTypeName(impl);
                if (!clean.isEmpty()) {
                    type.implementsTypes.add(clean);
                    addDependencies(clean, type);
                }
            }
        }
    }

    private void parseGenericConstraints(String headerRest, TypeModel type) {
        int lt = headerRest.indexOf('<');
        int gt = headerRest.lastIndexOf('>');
        if (lt < 0 || gt < 0 || gt <= lt) {
            return;
        }
        String generics = headerRest.substring(lt + 1, gt);
        for (String part : generics.split(",")) {
            String p = part.trim();
            int ext = p.indexOf("extends");
            if (ext >= 0) {
                String bound = p.substring(ext + "extends".length()).trim();
                for (String b : splitTypes(bound)) {
                    String clean = cleanTypeName(b);
                    if (!clean.isEmpty()) {
                        addDependencies(clean, type);
                    }
                }
            }
        }
    }

    private void parseTypeBody(String body, TypeModel type) {
        List<String> lines = splitTopLevelLines(body);
        for (String raw : lines) {
            String line = raw.replace('\n', ' ').trim();
            if (line.isEmpty()) {
                continue;
            }
            String lineNoModifiers = line.replaceAll("^(?:(?:public|private|protected|static|abstract|override|readonly|async)\\s+)*", "");
            if (lineNoModifiers.startsWith("constructor(") || lineNoModifiers.startsWith("constructor ")) {
                String paramsPart = extractParams(line);
                List<ParamInfo> params = parseParamInfos(paramsPart);
                List<String> paramTypes = new ArrayList<>();
                for (ParamInfo p : params) {
                    paramTypes.add(p.type);
                    addDependencies(p.type, type);
                    if (p.isProperty) {
                        type.fields.add(new FieldModel(p.name, p.type));
                        addDependencies(p.type, type);
                    }
                }
                type.methods.add(new MethodModel(type.name, "void", paramTypes));
                continue;
            }

            if (line.startsWith("abstract ") && line.endsWith(";")) {
                line = line.substring("abstract ".length()).trim();
            }

            if (line.contains("=") && line.contains("class")) {
                continue;
            }

            Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                String paramsPart = methodMatcher.group(2) == null ? "" : methodMatcher.group(2);
                String returnType = methodMatcher.group(3) == null ? "void" : methodMatcher.group(3).trim();
                List<String> params = parseParamTypes(paramsPart);
                type.methods.add(new MethodModel(methodName, returnType, params));
                addDependencies(returnType, type);
                for (String p : params) {
                    addDependencies(p, type);
                }
                continue;
            }

            Matcher fieldMatcher = FIELD_PATTERN.matcher(line);
            if (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(1);
                String fieldType = fieldMatcher.group(2).trim();
                fieldType = trimTypeSuffix(fieldType);
                type.fields.add(new FieldModel(fieldName, fieldType));
                addDependencies(fieldType, type);
            }
        }
    }

    private void parseTypeAliases(String content, ModelRepository repo) {
        Matcher aliasMatcher = TYPE_ALIAS_START_PATTERN.matcher(content);
        while (aliasMatcher.find()) {
            String name = aliasMatcher.group(1);
            int rhsStart = aliasMatcher.end();
            int rhsEnd = findTypeAliasEnd(content, rhsStart);
            if (rhsEnd < 0) {
                continue;
            }
            String rhs = content.substring(rhsStart, rhsEnd).trim();
            if (!rhs.contains("{")) {
                continue;
            }
            TypeModel type = repo.getOrCreate(name, TypeKind.INTERFACE);

            int idx = 0;
            while (idx < rhs.length()) {
                int open = rhs.indexOf('{', idx);
                if (open < 0) {
                    break;
                }
                int close = findMatchingBrace(rhs, open);
                if (close < 0) {
                    break;
                }
                String body = rhs.substring(open + 1, close);
                Matcher fieldMatcher = ALIAS_FIELD_PATTERN.matcher(body);
                while (fieldMatcher.find()) {
                    String fieldName = fieldMatcher.group(1);
                    String fieldType = trimTypeSuffix(fieldMatcher.group(2).trim());
                    type.fields.add(new FieldModel(fieldName, fieldType));
                    addDependencies(fieldType, type);
                }
                idx = close + 1;
            }
        }
    }

    private void parseEnumBody(String body, TypeModel type) {
        String cleaned = body.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return;
        }
        String[] parts = cleaned.split(",");
        for (String part : parts) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq >= 0) {
                entry = entry.substring(0, eq).trim();
            }
            if (!entry.isEmpty()) {
                type.fields.add(new FieldModel(entry, ""));
            }
        }
    }

    private List<String> splitTopLevelLines(String body) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int parenDepth = 0;
        boolean inString = false;
        char stringChar = '\0';

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);

            if (!inString) {
                if (c == '"' || c == '\'' || c == '`') {
                    inString = true;
                    stringChar = c;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                } else if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth = Math.max(0, parenDepth - 1);
                }
            } else {
                if (c == stringChar && (i == 0 || body.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            }

            if (c == '\n' && depth == 0 && parenDepth == 0) {
                lines.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private int findMatchingBrace(String content, int openIndex) {
        int braceCount = 0;
        boolean inString = false;
        char stringChar = '\0';

        for (int i = openIndex; i < content.length(); i++) {
            char c = content.charAt(i);

            if (!inString) {
                if (c == '"' || c == '\'' || c == '`') {
                    inString = true;
                    stringChar = c;
                } else if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        return i;
                    }
                }
            } else {
                if (c == stringChar && (i == 0 || content.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            }
        }
        return -1;
    }

    private int findTypeAliasEnd(String content, int start) {
        int braceDepth = 0;
        int angleDepth = 0;
        int parenDepth = 0;
        boolean inString = false;
        char stringChar = '\0';

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);

            if (!inString) {
                if (c == '"' || c == '\'' || c == '`') {
                    inString = true;
                    stringChar = c;
                } else if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth = Math.max(0, braceDepth - 1);
                } else if (c == '<') {
                    angleDepth++;
                } else if (c == '>') {
                    angleDepth = Math.max(0, angleDepth - 1);
                } else if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth = Math.max(0, parenDepth - 1);
                } else if (c == ';' && braceDepth == 0 && angleDepth == 0 && parenDepth == 0) {
                    return i;
                }
            } else {
                if (c == stringChar && (i == 0 || content.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            }
        }
        return -1;
    }

    private String removeComments(String content) {
        content = content.replaceAll("(?s)/\\*.*?\\*/", "");
        content = content.replaceAll("(?m)//.*$", "");
        return content;
    }

    private String extractParams(String line) {
        int open = line.indexOf('(');
        int close = line.indexOf(')');
        if (open < 0 || close < 0 || close <= open) {
            return "";
        }
        return line.substring(open + 1, close).trim();
    }

    private List<String> parseParamTypes(String paramsPart) {
        if (paramsPart.isEmpty()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        String[] parts = paramsPart.split(",");
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            p = p.replaceAll("^\\.{3}", "");
            p = p.replaceAll("^(?:(?:public|private|protected|readonly)\\s+)+", "");
            int colon = p.indexOf(':');
            String type = colon >= 0 ? p.substring(colon + 1).trim() : "any";
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

    private List<ParamInfo> parseParamInfos(String paramsPart) {
        List<ParamInfo> infos = new ArrayList<>();
        if (paramsPart.isEmpty()) {
            return infos;
        }
        String[] parts = paramsPart.split(",");
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            p = p.replaceAll("^\\.{3}", "");
            int eq = p.indexOf('=');
            String left = eq >= 0 ? p.substring(0, eq).trim() : p;
            boolean isProperty = left.matches("^(?:(?:public|private|protected|readonly)\\s+)+.*");
            left = left.replaceAll("^(?:(?:public|private|protected|readonly)\\s+)+", "");
            int colon = left.indexOf(':');
            String name = colon >= 0 ? left.substring(0, colon).trim() : left.trim();
            String type = colon >= 0 ? left.substring(colon + 1).trim() : "any";
            name = name.replace("?", "").trim();
            type = trimTypeSuffix(type);
            infos.add(new ParamInfo(name, type, isProperty));
        }
        return infos;
    }

    private String trimTypeSuffix(String type) {
        String t = type.trim();
        if (t.endsWith(",")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    private List<String> splitTypes(String typeList) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < typeList.length(); i++) {
            char c = typeList.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(typeList.substring(last, i).trim());
                last = i + 1;
            }
        }
        String tail = typeList.substring(last).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private String stripGenerics(String headerRest) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < headerRest.length(); i++) {
            char c = headerRest.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void addDependencies(String typeName, TypeModel type) {
        if (typeName == null || typeName.isBlank()) {
            return;
        }
        for (String dep : extractTypeNames(typeName)) {
            if (!isPrimitiveType(dep) && !dep.equals(type.name) &&
                    !type.extendsTypes.contains(dep) && !type.implementsTypes.contains(dep)) {
                type.dependencies.add(dep);
            }
        }
    }

    private List<String> extractTypeNames(String typeName) {
        String cleaned = cleanTypeName(typeName);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();

        for (String part : splitUnion(cleaned)) {
            String base = part.trim();
            if (base.endsWith("[]")) {
                base = base.substring(0, base.length() - 2).trim();
            }
            int lt = base.indexOf('<');
            if (lt < 0) {
                result.add(base);
                continue;
            }
            String outer = base.substring(0, lt).trim();
            if (!outer.isEmpty()) {
                result.add(outer);
            }
            String inner = base.substring(lt + 1, base.lastIndexOf('>')).trim();
            if (!inner.isEmpty()) {
                for (String arg : splitTypes(inner)) {
                    result.addAll(extractTypeNames(arg));
                }
            }
        }
        return result;
    }

    private List<String> splitUnion(String typeName) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < typeName.length(); i++) {
            char c = typeName.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if ((c == '|' || c == '&') && depth == 0) {
                parts.add(typeName.substring(last, i).trim());
                last = i + 1;
            }
        }
        String tail = typeName.substring(last).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private String cleanTypeName(String typeName) {
        String cleaned = typeName.trim();
        cleaned = cleaned.replace("?", "");
        cleaned = cleaned.replace("readonly ", "");
        cleaned = cleaned.replaceAll("\"[^\"]*\"", "");
        cleaned = cleaned.replaceAll("'[^']*'", "");
        return cleaned.trim();
    }

    private boolean isPrimitiveType(String type) {
        return PRIMITIVES.contains(type);
    }

    private static final class ParamInfo {
        private final String name;
        private final String type;
        private final boolean isProperty;

        private ParamInfo(String name, String type, boolean isProperty) {
            this.name = name;
            this.type = type;
            this.isProperty = isProperty;
        }
    }
}
