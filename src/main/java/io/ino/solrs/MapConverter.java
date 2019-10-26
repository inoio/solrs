package io.ino.solrs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapConverter {
    public static Map<String, List<String>> convert(Map<String, String[]> params) {
        Map<String, List<String>> result = new HashMap<>();
        params.forEach((key, value) -> result.put(key, Arrays.asList(value)));

        return result;
    }
}
