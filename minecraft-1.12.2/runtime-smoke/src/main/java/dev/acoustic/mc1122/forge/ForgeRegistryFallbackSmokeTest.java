package dev.acoustic.mc1122.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;

/** Guards the fork-registry fallback against aliasing RegistrySimple#getObject(K) as a values getter. */
public final class ForgeRegistryFallbackSmokeTest {
    public static final class IteratorOnlyRegistry {
        int getObjectCalls;
        public Iterator<Object> iterator() {
            return Arrays.<Object>asList("one", "two").iterator();
        }
        public Object func_82594_a(Object key) {
            getObjectCalls++;
            throw new AssertionError("func_82594_a/getObject(K) must never be used as zero-arg registry enumeration");
        }
    }

    public static void main(String[] args) throws Exception {
        Field companionField = ForgeBlockAcousticIntrospector.class.getField("Companion");
        Object companion = companionField.get(null);
        Method asIterable = companion.getClass().getDeclaredMethod("asIterable", Object.class);
        asIterable.setAccessible(true);
        IteratorOnlyRegistry registry = new IteratorOnlyRegistry();
        Object result = asIterable.invoke(companion, registry);
        if (!(result instanceof Iterable)) throw new AssertionError("iterator-only registry was not adapted");
        int count = 0;
        for (Object ignored : (Iterable<?>) result) count++;
        if (count != 2) throw new AssertionError("iterator-only registry count=" + count);
        if (registry.getObjectCalls != 0) throw new AssertionError("getObject alias was invoked");
        System.out.println("[PASS] iterator-only block registry fallback never aliases func_82594_a/getObject(K)");
    }
}
