package sa.com.cloudsolutions.antikythera.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Common abstract base class for build-system helpers.
 *
 * <p>Provides shared static utilities ({@link #findProjectRoot(String)},
 * {@link #parseJavaVersion(String)}) used by both {@link MavenHelper} and
 * {@link GradleHelper}, as well as the concrete {@link #copyTemplate(String, String...)}
 * template-copy helper and the abstract contracts that each build system must fulfil:
 * {@link #getJavaVersion()} and {@link #buildJarPaths()}.
 */
public abstract class BuildHelper {

    private static final Logger logger = LoggerFactory.getLogger(BuildHelper.class);

    // -------------------------------------------------------------------------
    // Shared static utilities
    // -------------------------------------------------------------------------

    /**
     * Resolves the project root directory from a (potentially nested) source base path.
     *
     * <p>Strips the trailing {@code /src/main/java} or {@code /src/test/java} segments
     * from {@code basePath} to arrive at the project root. If the resulting path does
     * not exist on disk the parent directory is tried as a final fallback.
     *
     * @param basePath the configured base path (e.g. from {@code Settings.getBasePath()})
     * @return the resolved project root {@link Path}, or {@code null} when {@code basePath}
     *         is {@code null}
     */
    public static Path findProjectRoot(String basePath) {
        if (basePath == null) {
            return null;
        }
        String normalized = basePath.replace('\\', '/');
        Path p;
        int mainIdx = normalized.indexOf("/src/main/java");
        int testIdx = normalized.indexOf("/src/test/java");
        if (mainIdx >= 0) {
            p = Paths.get(normalized.substring(0, mainIdx));
        } else if (testIdx >= 0) {
            p = Paths.get(normalized.substring(0, testIdx));
        } else {
            p = Paths.get(basePath);
        }

        if (!p.toFile().exists()) {
            Path parent = p.getParent();
            if (parent != null) {
                p = parent;
            }
        }
        return p;
    }

    /**
     * Parses a Java version string into an integer.
     *
     * <p>Handles both the modern format (e.g. {@code "17"}) and the legacy
     * {@code 1.x} format (e.g. {@code "1.8"} → {@code 8}).
     *
     * @param version the version string to parse
     * @return the version as a positive integer, or {@code -1} if {@code version}
     *         is {@code null}, empty, or cannot be parsed
     */
    public static int parseJavaVersion(String version) {
        if (version == null || version.isEmpty()) {
            return -1;
        }
        try {
            if (version.startsWith("1.")) {
                return Integer.parseInt(version.substring(2));
            }
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Shared concrete behaviour
    // -------------------------------------------------------------------------

    /**
     * Copies a template file from the classpath {@code templates/} directory to the
     * configured output path, optionally placing it in a sub-directory.
     *
     * @param filename the template file name (e.g. {@code "pom.xml"})
     * @param subPath  optional path components appended below {@code output_path}
     * @return the absolute path of the copied file, or {@code null} when
     *         {@link Settings#getOutputPath()} is not configured
     * @throws IOException if the template cannot be found or the copy fails
     */
    public String copyTemplate(String filename, String... subPath) throws IOException {
        String outputPath = Settings.getOutputPath();
        if (outputPath != null) {
            Path destinationPath = Path.of(outputPath, subPath);
            Files.createDirectories(destinationPath);
            String name = destinationPath + File.separator + filename;
            try (InputStream sourceStream = getClass().getClassLoader()
                        .getResourceAsStream("templates/" + filename);
                 FileOutputStream destStream = new FileOutputStream(name);
                 FileChannel destChannel = destStream.getChannel()) {
                if (sourceStream == null) {
                    throw new IOException("Template file not found: templates/" + filename);
                }
                destChannel.transferFrom(Channels.newChannel(sourceStream), 0, Long.MAX_VALUE);
            }
            return name;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Abstract contracts
    // -------------------------------------------------------------------------

    /**
     * Returns the Java source version declared in the project's build descriptor.
     *
     * @return the Java version as a positive integer (e.g. {@code 17}),
     *         or {@code 21} when no version can be determined
     */
    public abstract int getJavaVersion();

    /**
     * Resolves the project's declared dependencies to JAR files and populates
     * the internal artifact cache used by the static {@code getJarPaths()} method.
     */
    public abstract void buildJarPaths();
}
