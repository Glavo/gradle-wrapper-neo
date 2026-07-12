package org.glavo.gradle.wrapper.neo;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Updates the launch scripts and generated single-file source used by this project.
 *
 * <p>Files are copied byte-for-byte from the Wrapper source project. The POSIX launcher is
 * also made executable when the file system supports POSIX permissions.</p>
 */
@DisableCachingByDefault(because = "Updates files tracked in source control.")
public abstract class UpdateProjectWrapperTask extends DefaultTask {
    /** Creates a task that always checks and synchronizes the project Wrapper files. */
    public UpdateProjectWrapperTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    /**
     * Returns the directory containing the maintained launch scripts.
     *
     * @return the launcher source directory
     */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getLauncherDirectory();

    /**
     * Returns the generated single-file Wrapper source.
     *
     * @return the generated source file
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getGeneratedSourceFile();

    /**
     * Returns the destination of the POSIX launcher.
     *
     * @return the POSIX launcher file
     */
    @OutputFile
    public abstract RegularFileProperty getUnixScriptFile();

    /**
     * Returns the destination of the batch launcher.
     *
     * @return the batch launcher file
     */
    @OutputFile
    public abstract RegularFileProperty getBatchScriptFile();

    /**
     * Returns the destination of the PowerShell launcher.
     *
     * @return the PowerShell launcher file
     */
    @OutputFile
    public abstract RegularFileProperty getPowerShellScriptFile();

    /**
     * Returns the destination of the generated single-file Wrapper source.
     *
     * @return the generated source destination
     */
    @OutputFile
    public abstract RegularFileProperty getSourceFile();

    /** Copies the maintained Wrapper files to their project locations. */
    @TaskAction
    public void update() {
        Path launcherDirectory = getLauncherDirectory().get().getAsFile().toPath();
        Path unixScript = getUnixScriptFile().get().getAsFile().toPath();

        copyIfChanged(launcherDirectory.resolve("gradlew"), unixScript);
        copyIfChanged(launcherDirectory.resolve("gradlew.bat"), getBatchScriptFile().get().getAsFile().toPath());
        copyIfChanged(launcherDirectory.resolve("gradlew.ps1"), getPowerShellScriptFile().get().getAsFile().toPath());
        copyIfChanged(getGeneratedSourceFile().get().getAsFile().toPath(), getSourceFile().get().getAsFile().toPath());
        makeExecutable(unixScript);
    }

    private static void copyIfChanged(Path source, Path target) {
        try {
            byte[] content = Files.readAllBytes(source);
            if (Files.isRegularFile(target) && Arrays.equals(content, Files.readAllBytes(target))) {
                return;
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not update " + target + " from " + source, e);
        }
    }

    private static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            boolean changed = permissions.add(PosixFilePermission.OWNER_EXECUTE);
            changed |= permissions.add(PosixFilePermission.GROUP_EXECUTE);
            changed |= permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            if (changed) {
                Files.setPosixFilePermissions(file, permissions);
            }
        } catch (UnsupportedOperationException ignored) {
            // The file system does not expose POSIX permissions.
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make the Wrapper launcher executable: " + file, e);
        }
    }
}
