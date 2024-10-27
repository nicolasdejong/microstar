package net.microstar.common.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class SuppliedAtomicReference<T> {
    private final Supplier<T> supplier;
    private final AtomicReference<T> ref = new AtomicReference<>(null);

    public SuppliedAtomicReference(Supplier<T> supplier) {
        this.supplier = supplier;
    }
    public T get() {
        T result = ref.get();
        if(result == null) {
            synchronized(ref) { // make sure the supplier is not called multiple times by multiple threads
                result = ref.get();
                if(result == null) {
                    result = supplier.get();
                    ref.set(result);
                }
            }
        }
        return result;
    }
    public void reset() {
        ref.set(null);
    }
}
