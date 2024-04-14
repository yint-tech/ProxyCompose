package cn.iinti.proxycompose.utils;


import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashUtil {
    public static long murHash(String key) {
        return Hashing.goodFastHash(128)
                .hashString(key, StandardCharsets.UTF_8).asLong();
    }

    public static <T, K> T fetchConsistentRing(TreeMap<K, T> treeMap, K key) {
        if (treeMap.isEmpty()) {
            return null;
        }
        SortedMap<K, T> tailMap = treeMap.tailMap(key);
        if (tailMap.isEmpty()) {
            return treeMap.values().iterator().next();
        }
        return tailMap.values().iterator().next();
    }

    public static <K, T> Iterable<T> constantRingIt(TreeMap<K, T> treeMap, K key) {
        Iterator<Map.Entry<K, T>> tailIterator = treeMap.tailMap(key).entrySet().iterator();
        Iterator<Map.Entry<K, T>> headIterator = treeMap.entrySet().iterator();
        return () -> new Iterator<T>() {
            private T value;

            @Override
            public boolean hasNext() {
                if (tailIterator.hasNext()) {
                    value = tailIterator.next().getValue();
                    return true;
                }
                if (headIterator.hasNext()) {
                    Map.Entry<K, T> next = headIterator.next();
                    K nextKey = next.getKey();
                    if (treeMap.comparator().compare(nextKey, key) >= 0) {
                        value = null;
                        return false;
                    }
                    value = next.getValue();
                    return true;

                }
                return false;
            }

            @Override
            public T next() {
                return value;
            }
        };

    }
}
