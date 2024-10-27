package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicReferenceNotNullTest {

    @Test void nullValueWithoutDefaultShouldThrow() {
        final DynamicReferenceNotNull<String> ref = new DynamicReferenceNotNull<>(null);
        assertThrows(NullPointerException.class, ref::get);
        assertDoesNotThrow(() -> ref.setDefault(() -> "abc").get());
    }
}