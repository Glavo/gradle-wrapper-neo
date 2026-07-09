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

val generateGradlewWrapperNeo = tasks.register<GenerateSingleJavaWrapperTask>("generateGradlewWrapperNeo") {
    sourceDirectory.set(layout.projectDirectory.dir("src/main/java"))
    outputFile.set(layout.buildDirectory.file("bundle/GradlewWrapperNeo.java"))
}

val compileGradlewWrapperNeo = tasks.register<JavaCompile>("compileGradlewWrapperNeo") {
    dependsOn(generateGradlewWrapperNeo)
    source(generateGradlewWrapperNeo.flatMap { it.outputFile })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/gradlew-wrapper-neo"))
    options.encoding = "UTF-8"
}

tasks.build {
    dependsOn(compileGradlewWrapperNeo)
}
