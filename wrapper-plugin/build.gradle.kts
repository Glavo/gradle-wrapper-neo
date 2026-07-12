import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.1.1"
}

val wrapperBundle = configurations.create("wrapperBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    wrapperBundle(project(path = ":wrapper-source", configuration = "wrapperBundleElements"))
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    website.set("https://github.com/Glavo/gradle-wrapper-neo")
    vcsUrl.set("https://github.com/Glavo/gradle-wrapper-neo")

    plugins {
        create("gradleWrapperNeo") {
            id = "org.glavo.gradle-wrapper-neo"
            implementationClass = "org.glavo.gradle.wrapper.neo.plugin.GradleWrapperNeoPlugin"
            displayName = "Gradle Wrapper Neo"
            description = "Generates a source-based Gradle wrapper without storing a wrapper JAR in the project."
            tags.set(listOf("gradle-wrapper", "wrapper", "build-setup"))
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.compileJava {
    options.release.set(8)
}

tasks.processResources {
    from(wrapperBundle) {
        into("org/glavo/gradle/wrapper/neo/plugin/bundle")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "gradle.wrapper.neo.test-kit-dir",
        gradle.gradleUserHomeDir.resolve("gradle-wrapper-neo/test-kit")
    )
}
