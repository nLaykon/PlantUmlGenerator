package org.laykon.model;

import java.util.*;

public class TypeModel {
    public final String name;
    public final TypeKind kind;

    public final List<FieldModel> fields = new ArrayList<>();
    public final List<MethodModel> methods = new ArrayList<>();

    public final Set<String> extendsTypes = new HashSet<>();
    public final Set<String> implementsTypes = new HashSet<>();
    public final Set<String> dependencies = new HashSet<>();

    public TypeModel(String name, TypeKind kind) {
        this.name = name;
        this.kind = kind;
    }
}
