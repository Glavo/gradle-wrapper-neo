package org.glavo.gradle.wrapper.neo;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class GenerateSingleJavaWrapperTask extends DefaultTask {
    private static final String ORIGINAL_MAIN_CLASS_NAME = "GradleWrapperMain";
    private static final String PROJECT_PACKAGE_PREFIX = "org.gradle.";
    private static final String JSPECIFY_PACKAGE_PREFIX = "org.jspecify.annotations.";

    public GenerateSingleJavaWrapperTask() {
        getMainClassName().convention("GradlewWrapperNeo");
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

            compilationUnit.removePackageDeclaration();
            compilationUnit.getImports().clear();
            removeJSpecifyAnnotations(compilationUnit);
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
            return StaticJavaParser.parse(sourceFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse " + sourceFile, e);
        }
    }

    private static Map<String, String> collectImports(CompilationUnit compilationUnit, Map<String, ImportDeclaration> imports) {
        Map<String, String> projectStaticImports = new LinkedHashMap<>();

        for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
            String importName = importDeclaration.getNameAsString();

            if (importName.startsWith(JSPECIFY_PACKAGE_PREFIX)) {
                continue;
            }

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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeJSpecifyAnnotations(CompilationUnit compilationUnit) {
        compilationUnit.walk(Node.class, node -> {
            if (node instanceof NodeWithAnnotations) {
                NodeWithAnnotations annotatedNode = (NodeWithAnnotations) node;
                annotatedNode.getAnnotations().removeIf(annotation -> isJSpecifyAnnotation((AnnotationExpr) annotation));
            }
        });
    }

    private static boolean isJSpecifyAnnotation(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        return name.equals("NullMarked")
            || name.equals("Nullable")
            || name.equals("NullUnmarked")
            || name.equals(JSPECIFY_PACKAGE_PREFIX + "NullMarked")
            || name.equals(JSPECIFY_PACKAGE_PREFIX + "Nullable")
            || name.equals(JSPECIFY_PACKAGE_PREFIX + "NullUnmarked");
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
        output.append(" * Generated by the generateGradlewWrapperNeo Gradle task.\n");
        output.append(" */\n\n");

        List<String> importLines = new ArrayList<>();
        for (ImportDeclaration importDeclaration : imports) {
            importLines.add(importDeclaration.toString().trim());
        }

        Set<String> sortedImportLines = importLines.stream()
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
            Files.write(outputPath, output.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + outputPath, e);
        }
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
