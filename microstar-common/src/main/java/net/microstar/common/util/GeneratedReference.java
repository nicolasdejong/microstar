package net.microstar.common.util;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Simplified atomic reference with just a get() where the first call to get() calls
  * the generator to generate the value. The value can be reset which will lead to a
  * generator call the next time get() is called.
  */
public class GeneratedReference<T> {
    private final AtomicReference<T> ref = new AtomicReference<>();
    private final Supplier<T> generator;

    public GeneratedReference(Supplier<T> generator) {
        this.generator = generator;
    }

    /** Returns the value, except if that value is not set which calls the generator
      * whose value will be set and returned. It is guaranteed that generator is
      * called only once when no value is set, even when multiple threads ask for
      * it at the same time.
      */
    public T get() {
        @Nullable T result = ref.get();
        if(result == null) {
            synchronized (ref) { // compareAndSet does not guarantee single call to generator
                result = ref.get();
                if(result == null) {
                    result = generator.get();
                    ref.set(result);
                }
            }
        }
        return result;
    }

    /** Reset the value so that the next call to get() leads to a call to the generator */
    public void reset() {
        ref.set(null);
    }
}
