package sa.com.cloudsolutions.antikythera.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.FileWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GradleHelperTest {

    // -------------------------------------------------------------------------
    // findProjectRoot
    // -------------------------------------------------------------------------

    @Test
    void testFindProjectRootStripsMainJava(@TempDir Path tempDir) {
        String basePath = tempDir + "/src/main/java";
        Path root = BuildHelper.findProjectRoot(basePath);
        assertEquals(tempDir.toAbsolutePath(), root);
    }

    @Test
    void testFindProjectRootStripsTestJava(@TempDir Path tempDir) {
        String basePath = tempDir + "/src/test/java";
        Path root = BuildHelper.findProjectRoot(basePath);
        assertEquals(tempDir.toAbsolutePath(), root);
    }

    @Test
    void testFindProjectRootWithNull() {
        assertNull(BuildHelper.findProjectRoot(null));
    }

    // -------------------------------------------------------------------------
    // isGradleProject
    // -------------------------------------------------------------------------

    @Test
    void testIsGradleProjectWithBuildGradle(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("plugins { id 'java' }\n");
        }
        assertTrue(GradleHelper.isGradleProject(tempDir.toString()));
    }

    @Test
    void testIsGradleProjectWithBuildGradleKts(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE_KTS);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("plugins { java }\n");
        }
        assertTrue(GradleHelper.isGradleProject(tempDir.toString()));
    }

    @Test
    void testIsGradleProjectReturnsFalseWhenNoBuildFile(@TempDir Path tempDir) {
        assertFalse(GradleHelper.isGradleProject(tempDir.toString()));
    }

    // -------------------------------------------------------------------------
    // readBuildFile / parseDependencies
    // -------------------------------------------------------------------------

    @Test
    void testParseStringNotationGroovy(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("dependencies {\n");
            fw.write("    implementation 'org.springframework.boot:spring-boot-starter-web:3.1.0'\n");
            fw.write("    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.2'\n");
            fw.write("}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);

        List<GradleHelper.GradleDependency> deps = helper.getParsedDependencies();
        assertEquals(2, deps.size());
        assertEquals("org.springframework.boot", deps.get(0).groupId);
        assertEquals("spring-boot-starter-web", deps.get(0).artifactId);
        assertEquals("3.1.0", deps.get(0).version);
    }

    @Test
    void testParseStringNotationKotlinDsl(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE_KTS);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("dependencies {\n");
            fw.write("    implementation(\"com.fasterxml.jackson.core:jackson-databind:2.14.0\")\n");
            fw.write("    testImplementation(\"org.junit.jupiter:junit-jupiter:5.9.2\")\n");
            fw.write("}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);

        List<GradleHelper.GradleDependency> deps = helper.getParsedDependencies();
        assertEquals(2, deps.size());
        assertEquals("com.fasterxml.jackson.core", deps.get(0).groupId);
        assertEquals("jackson-databind", deps.get(0).artifactId);
        assertEquals("2.14.0", deps.get(0).version);
    }

    @Test
    void testParseMapNotation(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("dependencies {\n");
            fw.write("    implementation group: 'org.apache.commons', name: 'commons-lang3', version: '3.12.0'\n");
            fw.write("}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);

        List<GradleHelper.GradleDependency> deps = helper.getParsedDependencies();
        assertEquals(1, deps.size());
        assertEquals("org.apache.commons", deps.get(0).groupId);
        assertEquals("commons-lang3", deps.get(0).artifactId);
        assertEquals("3.12.0", deps.get(0).version);
    }

    @Test
    void testSkipsDependenciesWithVariableVersions(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("dependencies {\n");
            fw.write("    implementation \"org.springframework.boot:spring-boot-starter:${springBootVersion}\"\n");
            fw.write("    implementation 'com.example:mylib:1.0.0'\n");
            fw.write("}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);

        List<GradleHelper.GradleDependency> deps = helper.getParsedDependencies();
        // Variable-version dependency should be skipped
        assertEquals(1, deps.size());
        assertEquals("com.example", deps.get(0).groupId);
    }

    // -------------------------------------------------------------------------
    // getJavaVersion
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "sourceCompatibility = '17', 17",
        "sourceCompatibility = '11', 11",
        "sourceCompatibility = '1.8', 8",
        "sourceCompatibility = JavaVersion.VERSION_21, 21",
        "sourceCompatibility = JavaVersion.VERSION_17, 17",
        "targetCompatibility = '11', 11"
    })
    void testGetJavaVersionFromSourceCompatibility(String declaration, int expected,
            @TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("plugins { id 'java' }\n");
            fw.write(declaration + "\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);
        assertEquals(expected, helper.getJavaVersion());
    }

    @Test
    void testGetJavaVersionFromToolchain(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("java {\n");
            fw.write("    toolchain {\n");
            fw.write("        languageVersion = JavaLanguageVersion.of(17)\n");
            fw.write("    }\n");
            fw.write("}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);
        assertEquals(17, helper.getJavaVersion());
    }

    @Test
    void testGetJavaVersionFromKotlinDslToolchain(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE_KTS);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("java {\n");
            fw.write("    toolchain {\n");
            fw.write("        languageVersion.set(JavaLanguageVersion.of(21))\n");
            fw.write("    }\n");
            fw.write("}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);
        assertEquals(21, helper.getJavaVersion());
    }

    @Test
    void testGetJavaVersionDefaultsTo21WhenNoVersionDeclared(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("plugins { id 'java' }\n");
            fw.write("dependencies {}\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);
        assertEquals(21, helper.getJavaVersion());
    }

    @Test
    void testGetJavaVersionDefaultsTo21WhenNotLoaded() {
        GradleHelper helper = new GradleHelper();
        assertEquals(21, helper.getJavaVersion());
    }

    // -------------------------------------------------------------------------
    // getBuildFilePath
    // -------------------------------------------------------------------------

    @Test
    void testGetBuildFilePathIsNullBeforeRead() {
        GradleHelper helper = new GradleHelper();
        assertNull(helper.getBuildFilePath());
    }

    @Test
    void testGetBuildFilePathAfterRead(@TempDir Path tempDir) throws Exception {
        Path buildFile = tempDir.resolve(GradleHelper.BUILD_GRADLE);
        try (FileWriter fw = new FileWriter(buildFile.toFile())) {
            fw.write("plugins { id 'java' }\n");
        }

        GradleHelper helper = new GradleHelper();
        helper.readBuildFile(buildFile);
        assertNotNull(helper.getBuildFilePath());
        assertEquals(buildFile.toAbsolutePath(), helper.getBuildFilePath());
    }

    // -------------------------------------------------------------------------
    // GradleDependency.toString
    // -------------------------------------------------------------------------

    @Test
    void testGradleDependencyToString() {
        GradleHelper.GradleDependency dep = new GradleHelper.GradleDependency("com.example", "mylib", "1.0.0");
        assertEquals("com.example:mylib:1.0.0", dep.toString());
    }
}
