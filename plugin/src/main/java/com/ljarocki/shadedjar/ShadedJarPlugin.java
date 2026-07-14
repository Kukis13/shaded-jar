package com.ljarocki.shadedjar;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * Registers the {@code fatJar} task and, when the {@code java} plugin is present,
 * wires it to the project's runtime classpath and main output automatically.
 *
 * <pre>
 *   plugins { id 'java'; id 'com.ljarocki.shaded-jar' }
 *   shadedJar { mainClass = 'com.example.Main' }
 *   // then: ./gradlew fatJar
 * </pre>
 */
public class ShadedJarPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ShadedJarExtension ext = project.getExtensions()
                .create("shadedJar", ShadedJarExtension.class);
        ext.getArchiveClassifier().convention("all");

        // Resolved eagerly here (configuration time), not inside a lazy Provider —
        // the resulting File is a plain, serializable value the task property just
        // holds, rather than a live reference to Project/Gradle captured for use at
        // execution time, which would risk breaking the configuration cache.
        File packCacheDir = new File(project.getGradle().getGradleUserHomeDir(),
                "caches/shaded-jar/" + PackCache.SCHEMA_VERSION);

        TaskProvider<FatJarTask> fatJar = project.getTasks()
                .register("fatJar", FatJarTask.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Assembles a fat (uber) JAR with shaded-jar.");
                    task.getMainClass().convention(ext.getMainClass());
                    task.getRelocations().convention(ext.getRelocations());
                    task.getRelocationIncludes().convention(ext.getRelocationIncludes());
                    task.getRelocationExcludes().convention(ext.getRelocationExcludes());
                    task.getMinimize().convention(ext.getMinimize());
                    task.getMinimizeKeep().convention(ext.getMinimizeKeep());
                    task.getPackCacheDir().set(packCacheDir);
                    task.getArchiveFile().convention(
                            project.getLayout().getBuildDirectory().file(
                                    project.provider(() -> "libs/" + archiveName(project, ext))));
                });

        // Auto-wire against the Java plugin's model when it is applied.
        project.getPlugins().withId("java", p -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            Configuration runtime = project.getConfigurations().getByName("runtimeClasspath");
            fatJar.configure(task -> {
                // Project output first so its classes win duplicate names; deps after.
                task.getClasspath().from(main.getOutput());
                task.getClasspath().from(runtime);
                // Tracked separately too, so minimize()'s reachability pre-pass knows
                // which of the classpath's files are always-kept roots rather than
                // dependency jars actually eligible for dropping.
                task.getProjectOutput().from(main.getOutput());
            });
        });
    }

    private static String archiveName(Project project, ShadedJarExtension ext) {
        String version = String.valueOf(project.getVersion());
        String classifier = ext.getArchiveClassifier().getOrElse("all");
        StringBuilder sb = new StringBuilder(project.getName());
        if (!version.isEmpty() && !"unspecified".equals(version)) sb.append('-').append(version);
        if (classifier != null && !classifier.isEmpty()) sb.append('-').append(classifier);
        return sb.append(".jar").toString();
    }
}
