/*
 * Copyright 2003-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.build;

import com.gradle.CommonCustomUserDataGradlePlugin;
import com.gradle.enterprise.gradleplugin.GradleEnterpriseExtension;
import com.gradle.enterprise.gradleplugin.GradleEnterprisePlugin;
import com.gradle.scan.plugin.BuildScanExtension;
import io.github.gradlenexus.publishplugin.NexusPublishExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.PluginManager;
import org.gradle.api.provider.ProviderFactory;
import org.nosphere.gradle.github.ActionsPlugin;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;

public class MicronautSharedSettingsPlugin implements Plugin<Settings> {
    public static final String NEXUS_STAGING_PROFILE_ID = "11bd7bc41716aa";

    @Override
    public void apply(Settings settings) {
        PluginManager pluginManager = settings.getPluginManager();
        pluginManager.apply(GradleEnterprisePlugin.class);
        pluginManager.apply(CommonCustomUserDataGradlePlugin.class);
        GradleEnterpriseExtension ge = settings.getExtensions().getByType(GradleEnterpriseExtension.class);
        configureGradleEnterprise(settings, ge);
        applyPublishingPlugin(settings);
    }

    private void applyPublishingPlugin(Settings settings) {
        ProviderFactory providers = settings.getProviders();
        String ossUser = envOrSystemProperty(providers, "SONATYPE_USERNAME", "sonatypeOssUsername", "");
        String ossPass = envOrSystemProperty(providers, "SONATYPE_PASSWORD", "sonatypeOssPassword", "");
        if (!ossUser.isEmpty() && !ossPass.isEmpty()) {
            settings.getGradle().projectsLoaded(gradle -> configureNexusPublishing(gradle, ossUser, ossPass));
        }
    }

    private void configureNexusPublishing(Gradle gradle, String ossUser, String ossPass) {
        Project rootProject = gradle.getRootProject();
        rootProject.getPlugins().apply("io.github.gradle-nexus.publish-plugin");
        NexusPublishExtension nexusPublish = rootProject.getExtensions().getByType(NexusPublishExtension.class);
        nexusPublish.getRepositoryDescription().set("" + rootProject.getGroup() + ":" + rootProject.getName() + ":" + rootProject.getVersion());
        nexusPublish.getUseStaging().convention(!rootProject.getVersion().toString().endsWith("-SNAPSHOT"));
        nexusPublish.repositories(repos -> repos.create("sonatype", repo -> {
            repo.getAllowInsecureProtocol().convention(rootProject.getProviders().systemProperty("allowInsecurePublishing").forUseAtConfigurationTime().map(Boolean::parseBoolean).orElse(false));
            repo.getUsername().set(ossUser);
            repo.getPassword().set(ossPass);
            repo.getNexusUrl().set(uri(envOrSystemProperty(rootProject.getProviders(), "SONATYPE_REPO_URI", "sonatypeRepoUri", "https://s01.oss.sonatype.org/service/local/")));
            repo.getSnapshotRepositoryUrl().set(uri(envOrSystemProperty(rootProject.getProviders(), "SONATYPE_SNAPSHOT_REPO_URI", "sonatypeSnapshotsRepoUri", "https://s01.oss.sonatype.org/content/repositories/snapshots/")));
            repo.getStagingProfileId().set(NEXUS_STAGING_PROFILE_ID);
        }));
    }

    private static URI uri(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static String envOrSystemProperty(ProviderFactory providers, String envName, String propertyName, String defaultValue) {
        return providers.environmentVariable(envName)
                .forUseAtConfigurationTime()
                .orElse(providers.gradleProperty(propertyName).forUseAtConfigurationTime())
                .getOrElse(defaultValue);
    }

    private void configureGradleEnterprise(Settings settings, GradleEnterpriseExtension ge) {
        ProviderFactory providers = settings.getProviders();
        boolean isCI = guessCI(providers);
        configureBuildScansPublishing(ge, isCI);
        settings.getGradle().projectsLoaded(MicronautSharedSettingsPlugin::applyGitHubActionsPlugin);
    }

    private void configureBuildScansPublishing(GradleEnterpriseExtension ge, boolean isCI) {
        ge.setServer("https://ge.micronaut.io");
        ge.buildScan(buildScan -> {
            buildScan.publishAlways();
            if (isCI) {
                buildScan.setUploadInBackground(false);
            } else {
                publishIfAuthenticated(buildScan);
            }
            buildScan.capture(c ->
                    c.setTaskInputFiles(true)
            );
        });
    }

    private static void applyGitHubActionsPlugin(Gradle gradle) {
        gradle.getRootProject().getPluginManager().apply(ActionsPlugin.class);
    }

    private static void publishIfAuthenticated(BuildScanExtension ext) {
        try {
            ext.getClass().getMethod("publishIfAuthenticated").invoke(ext);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            System.err.println("Unable to set publish if authenticated on build scan extension");
        }
    }

    private static boolean guessCI(ProviderFactory providers) {
        return providers
                .environmentVariable("CI").forUseAtConfigurationTime()
                .flatMap(s -> // Not all workflows may have the enterprise key set
                        providers.environmentVariable("GRADLE_ENTERPRISE_ACCESS_KEY")
                                .forUseAtConfigurationTime()
                                .map(env -> true)
                                .orElse(false)
                )
                .orElse(false)
                .get();
    }
}
