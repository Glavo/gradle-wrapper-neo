import org.glavo.gradle.wrapper.neo.GenerateSingleJavaWrapperTask
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
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
    wrapperVersion.set(project.version.toString())
    projectUrl.set("https://github.com/Glavo/gradle-wrapper-neo")
    outputFile.set(layout.buildDirectory.file("bundle/GradleWrapperNeo.java"))
}

val compileGradleWrapperNeo = tasks.register<JavaCompile>("compileGradleWrapperNeo") {
    dependsOn(generateGradleWrapperNeo)
    source(generateGradleWrapperNeo.flatMap { it.outputFile })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/gradle-wrapper-neo"))
    options.encoding = "UTF-8"
    options.release.set(8)
}

val prepareWrapperBundle = tasks.register<Sync>("prepareWrapperBundle") {
    dependsOn(generateGradleWrapperNeo)
    into(layout.buildDirectory.dir("wrapper-bundle"))
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("gradlew", "gradlew.bat", "gradlew.ps1")
    }
    from(generateGradleWrapperNeo.flatMap { it.outputFile })
}

val wrapperBundleElements = configurations.create("wrapperBundleElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(wrapperBundleElements.name, prepareWrapperBundle.map { it.destinationDir }) {
        builtBy(prepareWrapperBundle)
        type = "directory"
    }
}

tasks.build {
    dependsOn(compileGradleWrapperNeo, prepareWrapperBundle)
}
