import com.github.jk1.license.LicenseReportExtension
import io.spring.gradle.bintray.SpringBintrayExtension
import nebula.plugin.contacts.Contact
import nebula.plugin.contacts.ContactsExtension
import nebula.plugin.info.InfoBrokerPlugin
import nl.javadude.gradle.plugins.license.LicenseExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig
import java.util.*

buildscript {
    repositories {
        jcenter()
        gradlePluginPortal()
    }

    dependencies {
        classpath("io.spring.gradle:spring-release-plugin:0.20.1")

        constraints {
            classpath("org.jfrog.buildinfo:build-info-extractor-gradle:4.13.0") {
                because("Need recent version for Gradle 6+ compatibility")
            }
        }
    }
}

plugins {
    id("io.spring.release") version "0.20.1"
    id("org.jetbrains.kotlin.jvm") version "1.4.21" apply false
    id("org.gradle.test-retry") version "1.1.6" apply false
    id("com.github.jk1.dependency-license-report") version "1.16" apply false
}

allprojects {
    apply(plugin = "license")
    group = "org.openrewrite"
    description = "Eliminate tech-debt. Automatically."
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "nebula.maven-resolved-dependencies")
    apply(plugin = "io.spring.publishing")
    apply(plugin = "org.gradle.test-retry")
    apply(plugin = "com.github.jk1.dependency-license-report")

    repositories {
        mavenCentral()
    }

    tasks.withType(GenerateModuleMetadata::class.java) {
        enabled = false
    }

    dependencies {
        "compileOnly"("com.google.code.findbugs:jsr305:latest.release")

        "compileOnly"("org.projectlombok:lombok:latest.release")
        "annotationProcessor"("org.projectlombok:lombok:latest.release")

        "testImplementation"("org.junit.jupiter:junit-jupiter-api:latest.release")
        "testImplementation"("org.junit.jupiter:junit-jupiter-params:latest.release")
        "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:latest.release")

        "testImplementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        "testImplementation"("org.jetbrains.kotlin:kotlin-stdlib-common")

        "testImplementation"("org.assertj:assertj-core:latest.release")

        "testRuntimeOnly"("ch.qos.logback:logback-classic:1.0.13")
    }

    // This eagerly realizes KotlinCompile tasks, which is undesirable from the perspective of minimizing
    // time spent during Gradle's configuration phase.
    // But if we don't proactively make sure the destination dir exists, sometimes JavaCompile can fail with:
    // '..rewrite-core\build\classes\java\main' specified for property 'compileKotlinOutputClasses' does not exist.
    tasks.withType(KotlinCompile::class.java) {
        kotlinOptions {
            jvmTarget = "1.8"
        }

        destinationDir.mkdirs()
    }

    // We use kotlin exclusively for tests
    // The kotlin plugin adds kotlin-stdlib dependencies to the main sourceSet, even if it doesn't use any kotlin
    // To avoid shipping dependencies we don't actually need, exclude them from the main sourceSet classpath but add them _back_ in for the test source sets
    configurations.all {
        if (project.name != "rewrite-test" && (name == "compileClasspath" || name == "runtimeClasspath")) {
            exclude(group = "org.jetbrains.kotlin")
        }
    }

    tasks.withType(JavaCompile::class.java) {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.named<JavaCompile>("compileJava") {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()

        options.isFork = true
        options.forkOptions.executable = "javac"
        options.compilerArgs.addAll(listOf("--release", "8"))
    }

    configure<ContactsExtension> {
        val j = Contact("jkschneider@gmail.com")
        j.moniker("Jonathan Schneider")

        people["jkschneider@gmail.com"] = j
    }

    configure<LicenseExtension> {
        ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
        skipExistingHeaders = true
        excludePatterns.addAll(listOf("**/*.tokens", "**/*.config", "**/*.interp"))
        header = project.rootProject.file("gradle/licenseHeader.txt")
        mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
        strictCheck = true
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("debug")
        }
        jvmArgs = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames")
    }

    configurations.all {
        resolutionStrategy.cacheDynamicVersionsFor(0, "seconds")
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    configure<LicenseReportExtension> {
        renderers = arrayOf(com.github.jk1.license.render.CsvReportRenderer())
    }

    configure<PublishingExtension> {
        publications {
            named("nebula", MavenPublication::class.java) {
                suppressPomMetadataWarningsFor("runtimeElements")

                pom.withXml {
                    (asElement().getElementsByTagName("dependencies").item(0) as org.w3c.dom.Element).let { dependencies ->
                        dependencies.getElementsByTagName("dependency").let { dependencyList ->
                            var i = 0
                            var length = dependencyList.length
                            while (i < length) {
                                (dependencyList.item(i) as org.w3c.dom.Element).let { dependency ->
                                    if ((dependency.getElementsByTagName("scope")
                                                    .item(0) as org.w3c.dom.Element).textContent == "provided"
                                        || (project.name != "rewrite-test"
                                                && (dependency.getElementsByTagName("groupId")
                                            .item(0) as org.w3c.dom.Element).textContent == "org.jetbrains.kotlin")) {
                                        dependencies.removeChild(dependency)
                                        i--
                                        length--
                                    }
                                }
                                i++
                            }
                        }
                    }
                }
            }
        }
    }

    configure<SpringBintrayExtension> {
        org = "openrewrite"
        repo = "maven"
    }

    project.withConvention(ArtifactoryPluginConvention::class) {
        setContextUrl("https://oss.jfrog.org/artifactory")
        publisherConfig.let {
            val repository: PublisherConfig.Repository = it.javaClass
                    .getDeclaredField("repository")
                    .apply { isAccessible = true }
                    .get(it) as PublisherConfig.Repository

            repository.setRepoKey("oss-snapshot-local")
            repository.setUsername(project.findProperty("bintrayUser"))
            repository.setPassword(project.findProperty("bintrayKey"))
        }
    }

    tasks.withType<GenerateMavenPom> {
        doLast {
            // because pom.withXml adds blank lines
            destination.writeText(
                destination.readLines().filter { it.isNotBlank() }.joinToString("\n")
            )
        }

        doFirst {
            val runtimeClasspath = configurations.getByName("runtimeClasspath")

            val gav = { dep: ResolvedDependency ->
                "${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}"
            }

            val observedDependencies = TreeSet<ResolvedDependency> { d1, d2 ->
                gav(d1).compareTo(gav(d2))
            }

            fun reduceDependenciesAtIndent(indent: Int):
                    (List<String>, ResolvedDependency) -> List<String> =
                    { dependenciesAsList: List<String>, dep: ResolvedDependency ->
                        dependenciesAsList + listOf(" ".repeat(indent) + dep.module.id.toString()) + (
                                if (observedDependencies.add(dep)) {
                                    dep.children
                                            .sortedBy(gav)
                                            .fold(emptyList(), reduceDependenciesAtIndent(indent + 2))
                                } else {
                                    // this dependency subtree has already been printed, so skip it
                                    emptyList()
                                }
                            )
                    }

            project.plugins.withType<InfoBrokerPlugin> {
                add("Resolved-Dependencies", runtimeClasspath
                        .resolvedConfiguration
                        .lenientConfiguration
                        .firstLevelModuleDependencies
                        .sortedBy(gav)
                        .fold(emptyList(), reduceDependenciesAtIndent(6))
                        .joinToString("\n", "\n", "\n" + " ".repeat(4)))
            }
        }
    }
}

defaultTasks("build")
