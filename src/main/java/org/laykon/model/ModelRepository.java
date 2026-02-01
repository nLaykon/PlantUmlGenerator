package org.laykon.model;

import java.util.*;

public class ModelRepository {

    private final Map<String, TypeModel> types = new HashMap<>();

    public TypeModel getOrCreate(String name, TypeKind kind) {
        return types.computeIfAbsent(name, n -> new TypeModel(n, kind));
    }

    public Collection<TypeModel> all() {
        return types.values();
    }

    public boolean contains(String name) {
        return types.containsKey(name);
    }
}
