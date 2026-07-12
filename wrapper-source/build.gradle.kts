import org.glavo.gradle.wrapper.neo.GenerateSingleJavaWrapperTask
import org.glavo.gradle.wrapper.neo.UpdateProjectWrapperTask
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

tasks.test {
    dependsOn(generateGradleWrapperNeo)
    inputs.file(generateGradleWrapperNeo.flatMap { it.outputFile })
    doFirst {
        systemProperty("gradle.wrapper.neo.test-source", generateGradleWrapperNeo.get().outputFile.get().asFile)
    }
}

val compileGradleWrapperNeo = tasks.register<JavaCompile>("compileGradleWrapperNeo") {
    dependsOn(generateGradleWrapperNeo)
    source(generateGradleWrapperNeo.flatMap { it.outputFile })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/gradle-wrapper-neo"))
    options.encoding = "UTF-8"
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

val prepareWrapperBundle = tasks.register<Sync>("prepareWrapperBundle") {
    dependsOn(generateGradleWrapperNeo)
    into(layout.buildDirectory.dir("wrapper-bundle"))
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("gradlew", "gradlew.bat", "gradlew.ps1")
    }
    from(generateGradleWrapperNeo.flatMap { it.outputFile })
}

tasks.register<UpdateProjectWrapperTask>("updateGradleWrapperNeo") {
    group = "Build Setup"
    description = "Updates this project's Gradle Wrapper Neo launchers and generated Java source."
    dependsOn(generateGradleWrapperNeo)

    launcherDirectory.set(layout.projectDirectory.dir("src/main/resources"))
    generatedSourceFile.set(generateGradleWrapperNeo.flatMap { it.outputFile })
    unixScriptFile.set(rootProject.layout.projectDirectory.file("gradlew"))
    batchScriptFile.set(rootProject.layout.projectDirectory.file("gradlew.bat"))
    powerShellScriptFile.set(rootProject.layout.projectDirectory.file("gradlew.ps1"))
    sourceFile.set(rootProject.layout.projectDirectory.file("gradle/wrapper/GradleWrapperNeo.java"))
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
