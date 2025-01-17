/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package io.micronaut.build.catalogs;

import io.micronaut.build.catalogs.tasks.VersionCatalogUpdate;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

import java.util.Arrays;
import java.util.Collections;

public class MicronautVersionCatalogUpdatePlugin implements Plugin<Project> {
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException("The " + MicronautVersionCatalogUpdatePlugin.class.getName() + " plugin must be applied on the root project only");
        }
        TaskContainer tasks = project.getTasks();
        TaskProvider<VersionCatalogUpdate> updater = tasks.register("updateVersionCatalogs", VersionCatalogUpdate.class, task -> {
            task.getCatalogsDirectory().convention(project.getLayout().getProjectDirectory().dir("gradle"));
            task.getOutputDirectory().convention(project.getLayout().getProjectDirectory().dir("gradle/updates"));
            task.getRejectedQualifiers().convention(Arrays.asList("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea"));
            task.getIgnoredModules().convention(Collections.emptySet());
            task.getAllowMajorUpdates().convention(false);
        });
        tasks.register("useLatestVersions", Copy.class, task -> {
            VersionCatalogUpdate dependent = updater.get();
            task.from(dependent.getOutputDirectory());
            task.into(dependent.getCatalogsDirectory());
        });
        tasks.register("dependencyUpdates", task -> task.setDescription("Compatibility task with the old update mechanism"));
    }
}
