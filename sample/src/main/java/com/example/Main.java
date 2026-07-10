package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises a class from each bundled dependency, so running the produced jar
 * proves the dependencies were merged in and load correctly.
 *
 * <p>Two things worth noting for the shaded-jar build:
 * <ul>
 *   <li>{@link ImmutableList} (Guava) is used directly. In the shaded build Guava
 *       is relocated to {@code com.example.shaded.guava}, and this class's own
 *       reference is rewritten to match — so it still runs.
 *   <li>{@code jdbcDrivers} is discovered via {@link DriverManager}, which uses
 *       {@code ServiceLoader} over {@code META-INF/services/java.sql.Driver}. Both
 *       H2 and PostgreSQL show up only because their service files were *merged*;
 *       a naive first-wins fat jar would list just one.
 * </ul>
 */
public class Main {
    public static void main(String[] args) throws Exception {
        ImmutableList<Integer> nums = ImmutableList.of(4, 8, 15, 16, 23, 42);

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (int n : nums) stats.addValue(n);

        // ServiceLoader-based discovery — proves META-INF/services merging.
        List<String> drivers = new ArrayList<>();
        Enumeration<Driver> e = DriverManager.getDrivers();
        while (e.hasMoreElements()) drivers.add(e.nextElement().getClass().getName());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guava", nums);
        out.put("guavaImplPackage", ImmutableList.class.getName());
        out.put("commons-math3.mean", stats.getMean());
        out.put("jdbcDrivers", drivers);

        String json = new ObjectMapper().writeValueAsString(out);
        System.out.println("shaded-jar sample OK -> " + json);
    }
}
