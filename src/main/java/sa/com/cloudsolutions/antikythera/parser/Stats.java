package sa.com.cloudsolutions.antikythera.parser;

/**
 * A simple data class to hold statistics about parsing and generation.
 */
public class Stats {
    int controllers;
    int methods;
    int tests = 0;

    /**
     * Gets the number of controllers processed.
     *
     * @return the number of controllers
     */
    public int getControllers() {
        return controllers;
    }

    /**
     * Gets the number of methods processed.
     *
     * @return the number of methods
     */
    public int getMethods() {
        return methods;
    }

    /**
     * Sets the number of tests generated.
     *
     * @param tests the number of tests
     */
    public void setTests(int tests) {
        this.tests = tests;
    }

    /**
     * Gets the number of tests generated.
     *
     * @return the number of tests
     */
    public int getTests() {
        return tests;
    }
}
