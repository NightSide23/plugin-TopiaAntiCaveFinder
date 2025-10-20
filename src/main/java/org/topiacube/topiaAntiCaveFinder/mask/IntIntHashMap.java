package org.topiacube.topiaAntiCaveFinder.mask;

import java.util.Arrays;

final class IntIntHashMap {

    static final int NO_VALUE = Integer.MIN_VALUE;
    private static final int EMPTY_KEY = Integer.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.6f;

    private int[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int threshold;

    IntIntHashMap() {
        this(16);
    }

    IntIntHashMap(int expectedSize) {
        int capacity = 1;
        while (capacity < expectedSize) {
            capacity <<= 1;
        }
        allocateArrays(capacity);
        size = 0;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int get(int key) {
        int index = probeIndex(key);
        if (index == -1) {
            return NO_VALUE;
        }
        return values[index];
    }

    int put(int key, int value) {
        ensureCapacity();
        int pos = findInsertPosition(key);
        int current = keys[pos];
        if (current == EMPTY_KEY) {
            keys[pos] = key;
            values[pos] = value;
            size++;
            return NO_VALUE;
        }
        int previous = values[pos];
        values[pos] = value;
        return previous;
    }

    int remove(int key) {
        int index = probeIndex(key);
        if (index == -1) {
            return NO_VALUE;
        }
        int oldValue = values[index];
        shiftKeys(index);
        size--;
        return oldValue;
    }

    void forEach(IntIntConsumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            if (key != EMPTY_KEY) {
                consumer.accept(key, values[i]);
            }
        }
    }

    private void ensureCapacity() {
        if (size + 1 <= threshold) {
            return;
        }
        rehash(keys.length << 1);
    }

    private void allocateArrays(int capacity) {
        keys = new int[capacity];
        values = new int[capacity];
        Arrays.fill(keys, EMPTY_KEY);
        Arrays.fill(values, NO_VALUE);
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
    }

    private void rehash(int newCapacity) {
        int[] oldKeys = keys;
        int[] oldValues = values;
        allocateArrays(newCapacity);
        size = 0;
        if (oldKeys == null) {
            return;
        }
        for (int i = 0; i < oldKeys.length; i++) {
            int key = oldKeys[i];
            if (key != EMPTY_KEY) {
                put(key, oldValues[i]);
            }
        }
    }

    private int probeIndex(int key) {
        int pos = mix(key) & mask;
        while (true) {
            int current = keys[pos];
            if (current == EMPTY_KEY) {
                return -1;
            }
            if (current == key) {
                return pos;
            }
            pos = (pos + 1) & mask;
        }
    }

    private int findInsertPosition(int key) {
        int pos = mix(key) & mask;
        while (true) {
            int current = keys[pos];
            if (current == EMPTY_KEY || current == key) {
                return pos;
            }
            pos = (pos + 1) & mask;
        }
    }

    private void shiftKeys(int start) {
        int pos = start;
        int last;
        while (true) {
            pos = (pos + 1) & mask;
            int current = keys[pos];
            if (current == EMPTY_KEY) {
                keys[start] = EMPTY_KEY;
                values[start] = NO_VALUE;
                return;
            }
            int slot = mix(current) & mask;
            if (start <= pos ? (start >= slot || slot > pos) : (start >= slot && slot > pos)) {
                keys[start] = current;
                values[start] = values[pos];
                start = pos;
            }
        }
    }

    private int mix(int x) {
        int h = x * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    @FunctionalInterface
    interface IntIntConsumer {
        void accept(int key, int value);
    }
}
