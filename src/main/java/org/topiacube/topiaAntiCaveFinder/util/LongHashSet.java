package org.topiacube.topiaAntiCaveFinder.util;

import java.util.Arrays;

public final class LongHashSet {
    private static final long EMPTY = Long.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.7f;

    private long[] table = new long[32];
    private int size;
    private int maxSize = calculateMaxSize(table.length);

    public LongHashSet() {
        Arrays.fill(table, EMPTY);
    }

    public boolean add(long value) {
        if ((size + 1) > maxSize) {
            expand();
        }
        int index = probe(value, table);
        long existing = table[index];
        if (existing == value) {
            return false;
        }
        table[index] = value;
        size++;
        return true;
    }

    public void clear() {
        Arrays.fill(table, EMPTY);
        size = 0;
    }

    private void expand() {
        int newCapacity = table.length << 1;
        long[] newTable = new long[newCapacity];
        Arrays.fill(newTable, EMPTY);
        for (long value : table) {
            if (value != EMPTY) {
                insert(value, newTable);
            }
        }
        table = newTable;
        maxSize = calculateMaxSize(newCapacity);
    }

    private void insert(long value, long[] target) {
        int index = probe(value, target);
        target[index] = value;
    }

    private int probe(long value, long[] target) {
        int mask = target.length - 1;
        int index = mix(value) & mask;
        while (true) {
            long existing = target[index];
            if (existing == EMPTY || existing == value) {
                return index;
            }
            index = (index + 1) & mask;
        }
    }

    private int calculateMaxSize(int capacity) {
        return Math.max(1, (int) (capacity * LOAD_FACTOR));
    }

    private int mix(long value) {
        value ^= (value >>> 33);
        value *= 0xff51afd7ed558ccdL;
        value ^= (value >>> 33);
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= (value >>> 33);
        return (int) value;
    }
}
