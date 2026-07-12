# Gradle Wrapper Neo

Gradle Wrapper Neo is an alternative to Gradle Wrapper. Its key features are:

1. No embedded binary files

   The traditional Gradle Wrapper requires projects to include a `gradle-wrapper.jar` binary, which is unacceptable for many projects.

   Gradle Wrapper Neo implements the functionality of Gradle Wrapper in a single Java file. Projects only need to include the `GradleWrapperNeo.java` source file, making it easier to audit and manage.

2. Configurable download mirrors

   In some regions, such as mainland China, downloading Gradle distributions through Gradle Wrapper can be slow.

   Gradle Wrapper Neo allows download mirrors to be configured in the user's home directory. This makes it possible to change the Gradle download source without modifying the project's `gradle-wrapper.properties` file, improving download speeds.

3. Drop-in replacement for Gradle Wrapper

   To migrate from Gradle Wrapper to Gradle Wrapper Neo, you only need to replace `gradlew`, `gradlew.bat`, and the `gradle-wrapper.jar` binary with their Gradle Wrapper Neo counterparts.
   No other changes are required. You can continue to build the project with `./gradlew` and manage the Gradle version through `gradle/wrapper/gradle-wrapper.properties`.

   Gradle Wrapper Neo also supports a non-embedded mode. You can install it on your computer and add its `gradlew` script to PATH.
   Then, in projects that still use Gradle Wrapper, run `gradlew` instead of `./gradlew`. Gradle Wrapper Neo will automatically take over the process,
   giving you access to features such as download mirrors without requiring any changes to the project.

TODO
