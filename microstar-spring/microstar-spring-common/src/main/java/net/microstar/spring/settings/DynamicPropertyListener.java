package net.microstar.spring.settings;

import java.util.Set;

public interface DynamicPropertyListener<T> {
    void changed(T newValue, Set<String> changedKeys);
    default void cleared() {}
}
