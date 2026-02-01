package org.laykon.model;

import java.util.List;

public class MethodModel {
    public final String name;
    public final String returnType;
    public final List<String> parameters;

    public MethodModel(String name, String returnType, List<String> parameters) {
        this.name = name;
        this.returnType = returnType;
        this.parameters = parameters;
    }
}
