plugins {
    `java-gradle-plugin`
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
    plugins {
        create("gradleWrapperNeo") {
            id = "org.glavo.gradle-wrapper-neo"
            implementationClass = "org.glavo.gradle.wrapper.neo.plugin.GradleWrapperNeoPlugin"
            displayName = "Gradle Wrapper Neo"
            description = "Generates a source-based Gradle wrapper without storing a wrapper JAR in the project."
        }
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
}
