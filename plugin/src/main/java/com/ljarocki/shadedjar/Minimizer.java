package com.ljarocki.shadedjar;

import org.vafer.jdependency.Clazz;
import org.vafer.jdependency.Clazzpath;
import org.vafer.jdependency.ClazzpathUnit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes which bundled dependency classes {@code minimize()} should drop:
 * anything reachable neither directly nor transitively from the project's
 * own compiled output, using {@code jdependency}'s {@link Clazzpath} (the
 * same library Shadow's own {@code minimize()} uses) rather than a hand-rolled
 * ASM reachability scanner — a hand-rolled scanner's failure mode (missing a
 * reference hidden in a descriptor/signature/annotation/invokedynamic
 * bootstrap arg, silently dropping a class that's actually used) is a much
 * worse risk than jdependency's own, already-proven coverage of those cases.
 *
 * <p>Pure/no-Gradle-deps, like {@link Zip64Support} and {@link
 * SpringProperties} — {@link FatJarTask} runs this once, sequentially,
 * before the parallel packing phase, since it needs the whole classpath's
 * picture at once rather than one source at a time.
 *
 * <p>Reflection, {@code ServiceLoader}-by-name and {@code Class.forName}
 * lookups are invisible to any static reachability analysis (jdependency's
 * included) — a class only ever found that way looks unreachable and would
 * be dropped unless explicitly kept (see {@link MinimizeSpec#keep}). This
 * mirrors Shadow's own documented {@code minimize()} caveat.
 */
final class Minimizer {

    private Minimizer() {
    }

    /**
     * @param projectOutputs the project's own compiled output roots (class/resource
     *     directories) — always fully kept, never eligible for dropping.
     * @param dependencySources dependency jars (or directories) on the runtime classpath.
     * @param keepPatterns exact dotted names or {@code .**} prefix wildcards (see
     *     {@link NamePatterns}) that are never dropped regardless of reachability.
     * @return dropped classes' internal (slash-form) names, e.g.
     *     {@code "com/google/common/collect/ImmutableList"} — matching a {@code .class}
     *     entry name with the suffix removed.
     */
    static Set<String> computeDropClasses(List<File> projectOutputs, List<File> dependencySources,
                                          Set<String> keepPatterns) throws IOException {
        Clazzpath cp = new Clazzpath();
        List<ClazzpathUnit> projectUnits = new ArrayList<>();
        for (File out : projectOutputs) {
            if (out.exists()) projectUnits.add(cp.addClazzpathUnit(out));
        }
        for (File dep : dependencySources) {
            if (dep.exists()) cp.addClazzpathUnit(dep);
        }

        Set<Clazz> reachableFromProject = new HashSet<>();
        for (ClazzpathUnit unit : projectUnits) {
            reachableFromProject.addAll(unit.getClazzes());
            reachableFromProject.addAll(unit.getTransitiveDependencies());
        }

        Set<Clazz> removable = new HashSet<>(cp.getClazzes());
        removable.removeAll(reachableFromProject);

        Set<String> drop = new HashSet<>();
        for (Clazz clazz : removable) {
            String dotName = clazz.getName();
            // package-info carries package-level annotations some frameworks scan
            // for; never worth the risk of stripping it even if "unreferenced".
            if (dotName.equals("package-info") || dotName.endsWith(".package-info")) continue;
            if (matchesAnyKeepPattern(dotName, keepPatterns)) continue;
            drop.add(dotName.replace('.', '/'));
        }
        return drop;
    }

    private static boolean matchesAnyKeepPattern(String dotName, Set<String> keepPatterns) {
        for (String pattern : keepPatterns) {
            if (NamePatterns.matches(pattern, dotName)) return true;
        }
        return false;
    }
}
