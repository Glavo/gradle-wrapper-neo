import org.gradle.api.tasks.Delete

plugins {
    base
}

group = "org.glavo"
version = providers.gradleProperty("version").getOrElse("0.2.0")

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

tasks.named<Delete>("clean") {
    setDelete(layout.buildDirectory.asFileTree)
    dependsOn(subprojects.map { "${it.path}:clean" })
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
