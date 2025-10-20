package org.topiacube.topiaAntiCaveFinder.util;

public final class LongArrayQueue {
    private static final int DEFAULT_CAPACITY = 32;

    private long[] elements = new long[DEFAULT_CAPACITY];
    private int head;
    private int tail;
    private int size;

    public void addLast(long value) {
        ensureCapacity(size + 1);
        elements[tail] = value;
        tail = (tail + 1) & (elements.length - 1);
        size++;
    }

    public long pollFirst() {
        if (size == 0) {
            throw new IllegalStateException("Queue is empty");
        }
        long value = elements[head];
        head = (head + 1) & (elements.length - 1);
        size--;
        return value;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        head = 0;
        tail = 0;
        size = 0;
    }

    private void ensureCapacity(int required) {
        if (elements.length >= required) {
            return;
        }
        int capacity = elements.length;
        while (capacity < required) {
            capacity <<= 1;
        }
        long[] newElements = new long[capacity];
        if (size > 0) {
            if (head < tail) {
                System.arraycopy(elements, head, newElements, 0, size);
            } else {
                int headPortion = elements.length - head;
                System.arraycopy(elements, head, newElements, 0, headPortion);
                System.arraycopy(elements, 0, newElements, headPortion, tail);
            }
        }
        elements = newElements;
        head = 0;
        tail = size;
    }
}
