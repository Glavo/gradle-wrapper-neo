plugins {
    base
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
