package org.glavo.gradle.wrapper.neo;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class GenerateSingleJavaWrapperTask extends DefaultTask {
    private static final String ORIGINAL_MAIN_CLASS_NAME = "GradleWrapperMain";
    private static final String PROJECT_PACKAGE_PREFIX = "org.gradle.";
    private static final String JSPECIFY_PACKAGE_PREFIX = "org.jspecify.annotations.";
    private static final Pattern JSPECIFY_ANNOTATION_PATTERN = Pattern.compile("@(?:org\\.jspecify\\.annotations\\.)?(NullMarked|Nullable|NullUnmarked)\\b");

    public GenerateSingleJavaWrapperTask() {
        getMainClassName().convention("GradleWrapperNeo");
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Input
    public abstract Property<String> getMainClassName();

    @TaskAction
    public void generate() {
        Path sourceRoot = getSourceDirectory().get().getAsFile().toPath();
        Path outputPath = getOutputFile().get().getAsFile().toPath();
        String mainClassName = getMainClassName().get();

        List<Path> sourceFiles = findJavaSources(sourceRoot);
        Map<String, ImportDeclaration> imports = new LinkedHashMap<>();
        List<String> transformedSources = new ArrayList<>();
        Map<String, Path> topLevelTypes = new LinkedHashMap<>();

        for (Path sourceFile : sourceFiles) {
            CompilationUnit compilationUnit = parse(sourceFile);
            Map<String, String> projectStaticImports = collectImports(compilationUnit, imports);

            compilationUnit.getImports().clear();
            rewriteProjectStaticImportUsages(compilationUnit, projectStaticImports);
            renameMainClassReferences(compilationUnit, mainClassName);
            configureTopLevelTypes(compilationUnit, mainClassName, sourceRoot, sourceFile, topLevelTypes);

            transformedSources.add(compilationUnit.toString().trim());
        }

        writeMergedSource(outputPath, imports.values(), transformedSources);
    }

    private static List<Path> findJavaSources(Path sourceRoot) {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                .sorted(Comparator.comparing(path -> sourceSortKey(sourceRoot, path)))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list Java sources under " + sourceRoot, e);
        }
    }

    private static String sourceSortKey(Path sourceRoot, Path sourceFile) {
        String relativePath = sourceRoot.relativize(sourceFile).toString().replace('\\', '/');
        if (relativePath.endsWith(ORIGINAL_MAIN_CLASS_NAME + ".java")) {
            return "0/" + relativePath;
        }
        return "1/" + relativePath;
    }

    private static CompilationUnit parse(Path sourceFile) {
        try {
            String source = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
            return StaticJavaParser.parse(prepareSourceForDefaultPackageMerge(source));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse " + sourceFile, e);
        }
    }

    private static String prepareSourceForDefaultPackageMerge(String source) {
        StringBuilder result = new StringBuilder(source.length());
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                break;
            }

            String line = lines[i];
            String trimmedLine = line.trim();
            String leadingWhitespace = line.substring(0, line.length() - line.stripLeading().length());
            if (trimmedLine.startsWith("import " + JSPECIFY_PACKAGE_PREFIX)) {
                continue;
            }

            if (trimmedLine.startsWith("package ") || isStandaloneJSpecifyAnnotation(trimmedLine)) {
                result.append(leadingWhitespace).append("// ").append(trimmedLine).append('\n');
            } else {
                result.append(commentOutInlineJSpecifyAnnotations(line)).append('\n');
            }
        }
        return result.toString();
    }

    private static boolean isStandaloneJSpecifyAnnotation(String line) {
        return line.equals("@NullMarked")
            || line.equals("@Nullable")
            || line.equals("@NullUnmarked")
            || line.startsWith("@NullMarked(")
            || line.startsWith("@Nullable(")
            || line.startsWith("@NullUnmarked(")
            || line.equals("@" + JSPECIFY_PACKAGE_PREFIX + "NullMarked")
            || line.equals("@" + JSPECIFY_PACKAGE_PREFIX + "Nullable")
            || line.equals("@" + JSPECIFY_PACKAGE_PREFIX + "NullUnmarked");
    }

    private static String commentOutInlineJSpecifyAnnotations(String line) {
        return JSPECIFY_ANNOTATION_PATTERN.matcher(line).replaceAll("/* // @$1 */");
    }

    private static Map<String, String> collectImports(CompilationUnit compilationUnit, Map<String, ImportDeclaration> imports) {
        Map<String, String> projectStaticImports = new LinkedHashMap<>();

        for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
            String importName = importDeclaration.getNameAsString();

            if (importDeclaration.isStatic() && importName.startsWith(PROJECT_PACKAGE_PREFIX)) {
                if (importDeclaration.isAsterisk()) {
                    throw new GradleException("Project static wildcard imports are not supported: " + importDeclaration);
                }
                StaticImportParts parts = StaticImportParts.parse(importName);
                projectStaticImports.put(parts.memberName, parts.ownerSimpleName);
                continue;
            }

            if (importName.startsWith(PROJECT_PACKAGE_PREFIX)) {
                continue;
            }

            imports.put(importDeclaration.toString().trim(), importDeclaration);
        }

        return projectStaticImports;
    }

    private static void rewriteProjectStaticImportUsages(CompilationUnit compilationUnit, Map<String, String> projectStaticImports) {
        if (projectStaticImports.isEmpty()) {
            return;
        }

        compilationUnit.findAll(MethodCallExpr.class).forEach(methodCall -> {
            if (!methodCall.getScope().isPresent()) {
                String ownerSimpleName = projectStaticImports.get(methodCall.getNameAsString());
                if (ownerSimpleName != null) {
                    methodCall.setScope(new NameExpr(ownerSimpleName));
                }
            }
        });

        compilationUnit.findAll(NameExpr.class).forEach(nameExpr -> {
            String ownerSimpleName = projectStaticImports.get(nameExpr.getNameAsString());
            if (ownerSimpleName != null) {
                nameExpr.replace(new FieldAccessExpr(new NameExpr(ownerSimpleName), nameExpr.getNameAsString()));
            }
        });
    }

    private static void renameMainClassReferences(CompilationUnit compilationUnit, String mainClassName) {
        compilationUnit.findAll(ClassOrInterfaceType.class).forEach(type -> {
            if (type.getNameAsString().equals(ORIGINAL_MAIN_CLASS_NAME)) {
                type.setName(mainClassName);
            }
        });
    }

    private static void configureTopLevelTypes(
        CompilationUnit compilationUnit,
        String mainClassName,
        Path sourceRoot,
        Path sourceFile,
        Map<String, Path> topLevelTypes
    ) {
        Path relativePath = sourceRoot.relativize(sourceFile);

        for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
            String originalName = type.getNameAsString();
            String effectiveName = originalName.equals(ORIGINAL_MAIN_CLASS_NAME) ? mainClassName : originalName;
            Path previousSource = topLevelTypes.putIfAbsent(effectiveName, relativePath);
            if (previousSource != null) {
                throw new GradleException("Duplicate top-level type '" + effectiveName + "' in " + previousSource + " and " + relativePath);
            }

            type.getModifiers().removeIf(modifier -> modifier.getKeyword() == Modifier.Keyword.PUBLIC);
            if (originalName.equals(ORIGINAL_MAIN_CLASS_NAME)) {
                type.setName(mainClassName);
                type.addModifier(Modifier.Keyword.PUBLIC);
            }
        }
    }

    private static void writeMergedSource(Path outputPath, Iterable<ImportDeclaration> imports, List<String> transformedSources) {
        StringBuilder output = new StringBuilder();
        output.append("/*\n");
        output.append(" * Generated by the generateGradleWrapperNeo Gradle task.\n");
        output.append(" */\n\n");

        List<String> importLines = new ArrayList<>();
        for (ImportDeclaration importDeclaration : imports) {
            importLines.add(importDeclaration.toString().trim());
        }

        Set<String> sortedImportLines = normalizeImportLines(importLines).stream()
            .sorted()
            .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String importLine : sortedImportLines) {
            output.append(importLine).append('\n');
        }

        output.append('\n');
        for (int i = 0; i < transformedSources.size(); i++) {
            if (i > 0) {
                output.append('\n');
            }
            output.append(transformedSources.get(i)).append('\n');
        }

        try {
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, normalizeGeneratedSource(output.toString()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + outputPath, e);
        }
    }

    private static String normalizeGeneratedSource(String source) {
        return normalizePackageCommentSpacing(normalizeCommentedInlineAnnotations(source));
    }

    private static String normalizePackageCommentSpacing(String source) {
        return source.replaceAll("(?m)^(\\s*// package [^\\r\\n]+)\\R(?!\\R)", "$1\n\n");
    }

    private static String normalizeCommentedInlineAnnotations(String source) {
        return source.replaceAll("(/\\* // @(?:NullMarked|Nullable|NullUnmarked) \\*/)\\R\\s+", "$1 ");
    }

    private static List<String> normalizeImportLines(List<String> importLines) {
        Set<String> wildcardImportedPackages = importLines.stream()
            .filter(importLine -> !importLine.startsWith("import static ") && importLine.endsWith(".*;"))
            .map(GenerateSingleJavaWrapperTask::packageNameFromWildcardImport)
            .collect(Collectors.toSet());

        return importLines.stream()
            .filter(importLine -> shouldKeepImportLine(importLine, wildcardImportedPackages))
            .collect(Collectors.toList());
    }

    private static boolean shouldKeepImportLine(String importLine, Set<String> wildcardImportedPackages) {
        if (importLine.startsWith("import static ") || importLine.endsWith(".*;")) {
            return true;
        }

        String importedType = importLine.substring("import ".length(), importLine.length() - 1);
        int packageSeparator = importedType.lastIndexOf('.');
        if (packageSeparator < 0) {
            return true;
        }

        String packageName = importedType.substring(0, packageSeparator);
        return !wildcardImportedPackages.contains(packageName);
    }

    private static String packageNameFromWildcardImport(String importLine) {
        return importLine.substring("import ".length(), importLine.length() - ".*;".length());
    }

    private static final class StaticImportParts {
        private final String ownerSimpleName;
        private final String memberName;

        private StaticImportParts(String ownerSimpleName, String memberName) {
            this.ownerSimpleName = ownerSimpleName;
            this.memberName = memberName;
        }

        private static StaticImportParts parse(String importName) {
            int memberSeparator = importName.lastIndexOf('.');
            if (memberSeparator < 0 || memberSeparator == importName.length() - 1) {
                throw new GradleException("Unsupported static import: " + importName);
            }

            String ownerName = importName.substring(0, memberSeparator);
            String memberName = importName.substring(memberSeparator + 1);
            int ownerSeparator = ownerName.lastIndexOf('.');
            String ownerSimpleName = ownerSeparator < 0 ? ownerName : ownerName.substring(ownerSeparator + 1);
            return new StaticImportParts(ownerSimpleName, memberName);
        }
    }
}
