package cn.iinti.proxycompose.utils;

import com.google.common.base.Splitter;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.TreeSet;

public class PortSpaceParser {
    private static final Map<String, TreeSet<Integer>> cache = Maps.newConcurrentMap();

    public static TreeSet<Integer> parsePortSpace(String config) {
        TreeSet<Integer> treeSet = cache.get(config);
        if (treeSet != null) {
            return new TreeSet<>(treeSet);
        }
        treeSet = parsePortSpaceImpl(config);
        cache.put(config, treeSet);
        return new TreeSet<>(treeSet);
    }

    public static TreeSet<Integer> parsePortSpaceImpl(String config) {
        TreeSet<Integer> copyOnWriteTreeSet = new TreeSet<>();
        Iterable<String> pairs = Splitter.on(":").split(config);
        for (String pair : pairs) {
            if (pair.contains("-")) {
                int index = pair.indexOf("-");
                String startStr = pair.substring(0, index);
                String endStr = pair.substring(index + 1);
                int start = Integer.parseInt(startStr);
                int end = Integer.parseInt(endStr);
                for (int i = start; i <= end; i++) {
                    copyOnWriteTreeSet.add(i);
                }
            } else {
                copyOnWriteTreeSet.add(Integer.parseInt(pair));
            }
        }
        return copyOnWriteTreeSet;

    }
}
