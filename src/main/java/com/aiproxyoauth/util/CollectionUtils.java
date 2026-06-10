package com.aiproxyoauth.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class CollectionUtils {

    private CollectionUtils() {}

    public static List<String> uniqueStrings(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }
}
