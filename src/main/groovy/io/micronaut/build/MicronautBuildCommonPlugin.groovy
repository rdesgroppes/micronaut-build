package io.micronaut.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.groovy.lang.groovydoc.tasks.GroovydocTask

/**
 * Micronaut internal Gradle plugin. Not intended to be used in user's projects.
 */
class MicronautBuildCommonPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply(MicronautBasePlugin)
        def micronautBuild = project.extensions.findByType(MicronautBuildExtension)
        configureJavaPlugin(project, micronautBuild)
        configureDependencies(project, micronautBuild)
        configureTasks(project)
        configureIdeaPlugin(project)
        configureLicensePlugin(project)
        configureTestLoggerPlugin(project)
        configureMiscPlugins(project)
        configureCheckstyle(project, micronautBuild)
    }

    private void configureDependencies(Project project, MicronautBuildExtension micronautBuild) {
        project.afterEvaluate {

            String micronautVersion = project.findProperty("micronautVersion")
            String groovyVersion = project.findProperty("groovyVersion")
            if (groovyVersion == null) {
                groovyVersion = project.extensions.findByType(VersionCatalogsExtension)
                        ?.find("libs")
                        ?.map {
                            it.findVersion("managed.groovy").get().requiredVersion
                        }
                        ?.orElse("undefined") ?: "no_version_catalog"
            }

            project.configurations {
                documentation
                all {
                    resolutionStrategy.eachDependency { DependencyResolveDetails details ->
                        String group = details.requested.group
                        if (group == 'org.codehaus.groovy') {
                            details.useVersion(groovyVersion)
                        }
                    }
                }
            }

            project.dependencies {
                if (micronautBuild.enableBom) {
                    if (micronautBuild.enforcedPlatform) {
                        throw new GradleException("Do not use enforcedPlatform. Please remove the micronautBuild.enforcedPlatform setting")
                    }
                    String p = "platform"
                    implementation "$p"("io.micronaut:micronaut-bom:${micronautVersion}")
                    annotationProcessor "$p"("io.micronaut:micronaut-bom:${micronautVersion}")
                    testAnnotationProcessor "$p"("io.micronaut:micronaut-bom:${micronautVersion}")
                    testImplementation "$p"("io.micronaut:micronaut-bom:${micronautVersion}")
                    compileOnly "$p"("io.micronaut:micronaut-bom:${micronautVersion}")
                }

                if (micronautBuild.enableProcessing) {
                    annotationProcessor "io.micronaut:micronaut-inject-groovy:${micronautVersion}"
                    testAnnotationProcessor "io.micronaut:micronaut-inject-groovy:${micronautVersion}"
                }

                documentation "org.codehaus.groovy:groovy-templates:$groovyVersion"
                documentation "org.codehaus.groovy:groovy-dateutil:$groovyVersion"

                if (project.hasProperty('micronautVersion')) {
                    testCompileOnly "io.micronaut:micronaut-inject-groovy:${micronautVersion}"
                }

                testImplementation "cglib:cglib-nodep:3.3.0"
                testImplementation "org.objenesis:objenesis:3.1"

                testRuntimeOnly "ch.qos.logback:logback-classic:1.2.3"
                testImplementation "org.codehaus.groovy:groovy-test:$groovyVersion"
            }
        }


        project.tasks.withType(GroovydocTask).configureEach {
            classpath += project.configurations.documentation
        }
    }

    private void configureJavaPlugin(Project project, MicronautBuildExtension micronautBuildExtension) {
        project.apply plugin: "groovy"
        project.apply plugin: "java-library"

        project.afterEvaluate {
            JavaPluginConvention convention = project.convention.getPlugin(JavaPluginConvention)
            convention.with {
                sourceCompatibility = micronautBuildExtension.sourceCompatibility
                targetCompatibility = micronautBuildExtension.targetCompatibility
            }
        }

        project.tasks.withType(Test).configureEach {
            jvmArgs '-Duser.country=US'
            jvmArgs '-Duser.language=en'

            reports.html.required = micronautBuildExtension.environment.isNotGithubAction()
            reports.junitXml.required = true

            String groovyVersion = project.findProperty("groovyVersion")
            if (groovyVersion?.startsWith("3")) {
                useJUnitPlatform()
            }
        }

        project.tasks.withType(GroovyCompile).configureEach {
            groovyOptions.forkOptions.jvmArgs.add('-Dgroovy.parameters=true')
        }

        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add('-parameters')
            if (micronautBuildExtension.enableProcessing) {
                options.compilerArgs.add("-Amicronaut.processing.group=$project.group")
                options.compilerArgs.add("-Amicronaut.processing.module=micronaut-$project.name")
            }
        }

        project.tasks.withType(Jar).configureEach {
            manifest {
                attributes('Automatic-Module-Name': "${project.group}.${project.name}".replaceAll('[^\\w\\.\\$_]', "_"))
                attributes('Implementation-Version': project.findProperty("projectVersion"))
                attributes('Implementation-Title': project.findProperty("title"))
            }
        }
    }

    void configureTasks(Project project) {
        project.tasks.register("allDeps", DependencyReportTask)
    }

    void configureIdeaPlugin(Project project) {
        project.with {
            apply plugin: 'idea'
            idea {
                module {
                    outputDir file('build/classes/java/main')
                    testOutputDir file('build/classes/groovy/test')
                }
            }
        }
    }

    void configureCheckstyle(Project project, MicronautBuildExtension micronautBuildExtension) {
        project.afterEvaluate {
            project.with {
                apply plugin: 'checkstyle'
                checkstyle {
                    configFile = file("${rootDir}/config/checkstyle/checkstyle.xml")
                    toolVersion = micronautBuildExtension.checkstyleVersion

                    // Per submodule
                    maxErrors = 1
                    maxWarnings = 10

                    showViolations = true
                }
                tasks.named('checkstyleTest') {
                    enabled = false
                }
                tasks.named('checkstyleMain') {
                    dependsOn('spotlessCheck')
                }
            }
        }
    }

    void configureLicensePlugin(Project project) {
        project.with {
            apply plugin: "com.diffplug.spotless"
            boolean hasGroovySources = file("src/main/groovy").exists()
            boolean hasKotlinSources = file("src/main/kotlin").exists()

            spotless {
                java {
                    licenseHeaderFile rootProject.file('config/spotless.license.java')
                    target 'src/main/java/**'
                }
                if (hasGroovySources) {
                    groovy {
                        licenseHeaderFile rootProject.file('config/spotless.license.java')
                        target 'src/main/groovy/**'
                    }
                }
                if (hasKotlinSources) {
                    kotlin {
                        licenseHeaderFile rootProject.file('config/spotless.license.java')
                        target 'src/main/kotlin/**'
                    }
                }
                format 'javaMisc', {
                    target 'src/main/**/package-info.java', 'src/main/**/module-info.java'
                    licenseHeaderFile rootProject.file('config/spotless.license.java'), '\\/\\*\\*'
                }
            }
        }
    }

    void configureTestLoggerPlugin(Project project) {
        project.with {
            apply plugin: "com.adarshr.test-logger"

            testlogger {
                theme 'standard-parallel'
                showFullStackTraces true
                showStandardStreams true
                showPassedStandardStreams false
                showSkippedStandardStreams false
                showFailedStandardStreams true
            }
        }
    }

    void configureMiscPlugins(Project project) {
        project.with {
            apply plugin: "io.spring.nohttp"
            nohttp {
                source.exclude "src/test/**", "build/**"
            }

        }
    }
}
