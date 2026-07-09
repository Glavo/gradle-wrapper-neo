import org.glavo.gradle.wrapper.neo.GenerateSingleJavaWrapperTask
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("java")
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val generateGradleWrapperNeo = tasks.register<GenerateSingleJavaWrapperTask>("generateGradleWrapperNeo") {
    sourceDirectory.set(layout.projectDirectory.dir("src/main/java"))
    outputFile.set(layout.buildDirectory.file("bundle/GradleWrapperNeo.java"))
}

val compileGradleWrapperNeo = tasks.register<JavaCompile>("compileGradleWrapperNeo") {
    dependsOn(generateGradleWrapperNeo)
    source(generateGradleWrapperNeo.flatMap { it.outputFile })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/gradlew-wrapper-neo"))
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.build {
    dependsOn(compileGradleWrapperNeo)
}
