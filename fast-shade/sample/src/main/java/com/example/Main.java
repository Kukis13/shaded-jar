package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exercises a class from each bundled dependency, so running the produced fat jar
 * actually proves every dependency's classes were merged in and load correctly.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        ImmutableList<Integer> nums = ImmutableList.of(4, 8, 15, 16, 23, 42);

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (int n : nums) stats.addValue(n);

        // Prove the H2 driver class is present (no DB needed for the check).
        Class.forName("org.h2.Driver");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guava", nums);
        out.put("commons-math3.mean", stats.getMean());
        out.put("h2", "driver loaded");

        String json = new ObjectMapper().writeValueAsString(out);
        System.out.println("shaded-jar sample OK -> " + json);
    }
}
