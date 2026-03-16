package sa.com.cloudsolutions.antikythera.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles Gradle project structure detection, build file reading, and JAR dependency resolution.
 * Supports both Groovy DSL (build.gradle) and Kotlin DSL (build.gradle.kts) build files.
 *
 * <p>The Gradle local module cache is located at
 * {@code ~/.gradle/caches/modules-2/files-2.1/<groupId>/<artifactId>/<version>/<hash>/<jar>}.
 * Unlike Maven's {@code ~/.m2/repository}, the group ID is stored with dots (not slashes) and
 * each version directory contains a content-addressable hash sub-directory.
 */
public class GradleHelper extends BuildHelper {

    public static final String BUILD_GRADLE = "build.gradle";
    public static final String BUILD_GRADLE_KTS = "build.gradle.kts";
    public static final String SETTINGS_GRADLE = "settings.gradle";
    public static final String SETTINGS_GRADLE_KTS = "settings.gradle.kts";

    private static final Logger logger = LoggerFactory.getLogger(GradleHelper.class);
    private static final Map<String, GradleArtifact> artifacts = new HashMap<>();
    private static volatile boolean jarPathsBuilt = false;

    private Path buildFilePath;
    private String buildFileContent;
    private final List<GradleDependency> dependencies = new ArrayList<>();

    /**
     * Matches string-notation dependencies in both Groovy and Kotlin DSL, e.g.:
     * <pre>
     *   implementation 'group:artifact:version'
     *   testImplementation("group:artifact:version")
     * </pre>
     * Capture groups: (1) groupId, (2) artifactId, (3) version.
     * The optional parentheses and single/double quotes cover both DSL flavours.
     */
    private static final Pattern DEPENDENCY_STRING_PATTERN = Pattern.compile(
        "\\b(?:implementation|api|compile|testImplementation|testCompile|compileOnly" +
        "|runtimeOnly|annotationProcessor|kapt)\\s*[\\(]?\\s*['\"]" +
        "([^'\":\\s]+):([^'\":\\s]+):([^'\"\\s\\)]+)['\"]\\s*[\\)]?"
    );

    /**
     * Matches map-notation dependencies in Groovy DSL, e.g.:
     * <pre>
     *   implementation group: 'org.apache.commons', name: 'commons-lang3', version: '3.12.0'
     * </pre>
     * Capture groups: (1) groupId, (2) artifactId, (3) version.
     */
    private static final Pattern DEPENDENCY_MAP_PATTERN = Pattern.compile(
        "\\b(?:implementation|api|compile|testImplementation|testCompile|compileOnly|runtimeOnly" +
        "|annotationProcessor)\\s+group:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name:\\s*['\"]([^'\"]+)['\"]" +
        "\\s*,\\s*version:\\s*['\"]([^'\"]+)['\"]"
    );

    /**
     * Matches {@code sourceCompatibility} / {@code targetCompatibility} assignments, e.g.:
     * <pre>
     *   sourceCompatibility = '17'
     *   sourceCompatibility = JavaVersion.VERSION_17
     * </pre>
     * Capture group (1) is the version number from {@code JavaVersion.VERSION_X} form.
     * Capture group (2) is the version from a plain string or number form.
     */
    private static final Pattern SOURCE_COMPAT_PATTERN = Pattern.compile(
        "(?:sourceCompatibility|targetCompatibility)\\s*(?:=|:=)\\s*" +
        "(?:JavaVersion\\.VERSION_(\\d+)|['\"]?([\\d.]+)['\"]?)"
    );

    /**
     * Matches Java toolchain declarations, e.g.:
     * <pre>
     *   languageVersion = JavaLanguageVersion.of(17)
     *   languageVersion.set(JavaLanguageVersion.of(17))
     * </pre>
     * Capture group (1) is the numeric Java version.
     */
    private static final Pattern TOOLCHAIN_PATTERN = Pattern.compile(
        "languageVersion(?:\\.set)?\\s*(?:=|\\()\\s*JavaLanguageVersion\\.of\\((\\d+)\\)"
    );

    // -------------------------------------------------------------------------
    // Project-type detection
    // -------------------------------------------------------------------------

    /**
     * Checks whether the project at the configured base path is a Gradle project.
     *
     * @return {@code true} when a {@code build.gradle} or {@code build.gradle.kts} is found
     */
    public static boolean isGradleProject() {
        return isGradleProject(Settings.getBasePath());
    }

    /**
     * Checks whether the project at the given directory path is a Gradle project.
     *
     * @param dirPath the base path to examine
     * @return {@code true} when a Gradle build file is found
     */
    public static boolean isGradleProject(String dirPath) {
        if (dirPath == null) {
            return false;
        }
        Path root = findProjectRoot(dirPath);
        if (root == null) {
            return false;
        }
        return Files.exists(root.resolve(BUILD_GRADLE))
                || Files.exists(root.resolve(BUILD_GRADLE_KTS));
    }

    // -------------------------------------------------------------------------
    // Build-file reading
    // -------------------------------------------------------------------------

    /**
     * Reads the Gradle build file found in the project root derived from the configured
     * {@code base_path}.
     *
     * @throws IOException when no Gradle build file can be located or read
     */
    public void readBuildFile() throws IOException {
        String basePath = Settings.getBasePath();
        Path root = findProjectRoot(basePath);
        if (root == null) {
            throw new IOException("Cannot determine project root from base path: " + basePath);
        }
        Path gradleFile = root.resolve(BUILD_GRADLE);
        if (!Files.exists(gradleFile)) {
            gradleFile = root.resolve(BUILD_GRADLE_KTS);
        }
        if (!Files.exists(gradleFile)) {
            throw new IOException("No Gradle build file found in: " + root);
        }
        readBuildFile(gradleFile);
    }

    /**
     * Reads a Gradle build file from a specific path and parses its dependency declarations.
     *
     * @param p path to {@code build.gradle} or {@code build.gradle.kts}
     * @throws IOException when the file cannot be read
     */
    public void readBuildFile(Path p) throws IOException {
        buildFilePath = p.toAbsolutePath();
        buildFileContent = new String(Files.readAllBytes(p));
        parseDependencies();
    }

    /**
     * Returns the absolute path of the currently loaded build file.
     *
     * @return the build-file {@link Path}, or {@code null} if no file has been loaded
     */
    public Path getBuildFilePath() {
        return buildFilePath;
    }

    /**
     * Returns the dependency declarations parsed from the build file.
     *
     * @return an unmodifiable view of the parsed dependency list
     */
    public List<GradleDependency> getParsedDependencies() {
        return dependencies;
    }

    // -------------------------------------------------------------------------
    // Dependency parsing
    // -------------------------------------------------------------------------

    private void parseDependencies() {
        if (buildFileContent == null) {
            return;
        }

        Matcher m = DEPENDENCY_STRING_PATTERN.matcher(buildFileContent);
        while (m.find()) {
            String version = m.group(3);
            if (!version.contains("${")) {
                dependencies.add(new GradleDependency(m.group(1), m.group(2), version));
            }
        }

        m = DEPENDENCY_MAP_PATTERN.matcher(buildFileContent);
        while (m.find()) {
            String version = m.group(3);
            if (!version.contains("${")) {
                dependencies.add(new GradleDependency(m.group(1), m.group(2), version));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Java-version extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts the Java source version from the Gradle build file.
     *
     * <p>The following constructs are recognised (in priority order):
     * <ol>
     *   <li>Java toolchain: {@code languageVersion = JavaLanguageVersion.of(17)}</li>
     *   <li>Source compatibility: {@code sourceCompatibility = '17'} or
     *       {@code sourceCompatibility = JavaVersion.VERSION_17}</li>
     * </ol>
     *
     * @return the Java version as an integer, or {@code 21} when no version is found
     */
    public int getJavaVersion() {
        if (buildFileContent == null) {
            return 21;
        }

        Matcher m = TOOLCHAIN_PATTERN.matcher(buildFileContent);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        m = SOURCE_COMPAT_PATTERN.matcher(buildFileContent);
        if (m.find()) {
            String versionStr = m.group(1) != null ? m.group(1) : m.group(2);
            if (versionStr != null) {
                int version = parseJavaVersion(versionStr);
                if (version > 0) {
                    return version;
                }
            }
        }

        return 21;
    }

    // -------------------------------------------------------------------------
    // JAR-path resolution (static API, mirrors MavenHelper)
    // -------------------------------------------------------------------------

    /**
     * Gets the absolute paths to all JAR files resolved from the Gradle project's dependencies.
     * The paths are resolved lazily on first invocation and cached for subsequent calls.
     *
     * @return an array of absolute JAR-file paths (never {@code null})
     */
    public static String[] getJarPaths() {
        if (!jarPathsBuilt) {
            initializeJarPaths();
        }
        List<String> paths = new ArrayList<>();
        for (GradleArtifact artifact : artifacts.values()) {
            if (artifact.jarFile != null) {
                paths.add(artifact.jarFile);
            }
        }
        return paths.toArray(new String[0]);
    }

    private static synchronized void initializeJarPaths() {
        if (jarPathsBuilt) return;
        try {
            GradleHelper helper = new GradleHelper();
            helper.readBuildFile();
            logger.debug("Read Gradle build file from: {}", helper.buildFilePath);
            logger.debug("Found {} dependencies in build file", helper.dependencies.size());
            helper.buildJarPaths();
            logger.debug("Built {} Gradle jar paths", artifacts.size());
            jarPathsBuilt = true;
        } catch (Exception e) {
            logger.warn("Could not build Gradle JAR paths: {}", e.getMessage());
            jarPathsBuilt = true; // prevent repeated failures
        }
    }

    /**
     * Resolves the dependencies collected from the build file to JAR files in the local
     * Gradle cache ({@code ~/.gradle/caches/modules-2/files-2.1}).
     *
     * <p>The cache root can be overridden via the {@code variables.gradle_cache} setting.
     */
    public void buildJarPaths() {
        String gradleCache = Settings.getProperty("variables.gradle_cache", String.class)
                .orElseGet(() -> {
                    String home = System.getProperty("user.home");
                    return home != null ? home + "/.gradle/caches/modules-2/files-2.1" : null;
                });

        if (gradleCache == null) {
            logger.warn("Gradle cache directory could not be determined; skipping JAR resolution");
            return;
        }

        for (GradleDependency dep : dependencies) {
            try {
                resolveJar(dep, gradleCache);
            } catch (IOException e) {
                logger.debug("Could not resolve Gradle dependency {}: {}", dep, e.getMessage());
            }
        }
    }

    /**
     * Locates the JAR file for a single dependency inside the Gradle module cache.
     *
     * <p>Cache layout: {@code <gradleCache>/<groupId>/<artifactId>/<version>/<hash>/<jarFile>}
     */
    private void resolveJar(GradleDependency dep, String gradleCache) throws IOException {
        Path depDir = Paths.get(gradleCache, dep.groupId, dep.artifactId, dep.version);

        if (!Files.exists(depDir) || !Files.isDirectory(depDir)) {
            logger.debug("Gradle cache entry not found: {}", depDir);
            return;
        }

        try (DirectoryStream<Path> hashDirs = Files.newDirectoryStream(depDir)) {
            for (Path hashDir : hashDirs) {
                if (Files.isDirectory(hashDir)) {
                    Path jarPath = hashDir.resolve(dep.artifactId + "-" + dep.version + ".jar");
                    if (Files.exists(jarPath)) {
                        String key = dep.groupId + ":" + dep.artifactId;
                        if (!artifacts.containsKey(key)) {
                            artifacts.put(key, new GradleArtifact(dep.artifactId, dep.version, jarPath.toString()));
                            logger.debug("Found Gradle JAR: {}", jarPath);
                        }
                        return;
                    }
                }
            }
        }
        logger.debug("JAR not found in Gradle cache for {}: {}", dep, depDir);
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Represents a single dependency declaration extracted from a Gradle build file.
     */
    public static class GradleDependency {
        public final String groupId;
        public final String artifactId;
        public final String version;

        public GradleDependency(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    static class GradleArtifact {
        final String name;
        final String version;
        final String jarFile;

        GradleArtifact(String name, String version, String jarFile) {
            this.name = name;
            this.version = version;
            this.jarFile = jarFile;
        }
    }
}
