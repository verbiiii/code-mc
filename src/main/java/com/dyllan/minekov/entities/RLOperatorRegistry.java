package com.dyllan.minekov.entities;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class RLOperatorRegistry {
    private static final Set<RLOperator> activeOperators =
        Collections.newSetFromMap(new WeakHashMap<>());

    public static void register(RLOperator op) {
        activeOperators.add(op);
    }

    public static void unregister(RLOperator op) {
        activeOperators.remove(op);
    }

    public static Set<RLOperator> getAll() {
        return activeOperators;
    }
}
