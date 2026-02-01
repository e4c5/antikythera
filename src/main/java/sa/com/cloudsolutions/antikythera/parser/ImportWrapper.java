package sa.com.cloudsolutions.antikythera.parser;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * Wraps an ImportDeclaration to provide additional context like the resolved Class or TypeDeclaration.
 */
public class ImportWrapper {
    ImportDeclaration imp;
    private Class<?> clazz;
    private TypeDeclaration<?> type;
    private FieldDeclaration fieldDeclaration;
    private MethodDeclaration methodDeclaration;
    /**
     * If the import is a wild card, this will represent the actual full import classname
     */
    private ImportDeclaration simplified;

    /**
     * Constructs an ImportWrapper with a resolved Class.
     *
     * @param imp the import declaration
     * @param clazz the resolved class
     */
    public ImportWrapper(ImportDeclaration imp, Class<?> clazz) {
        this.imp = imp;
        this.clazz = clazz;
    }

    /**
     * Constructs an ImportWrapper with just the import declaration.
     *
     * @param imp the import declaration
     */
    public ImportWrapper(ImportDeclaration imp) {
        this.imp = imp;
    }

    /**
     * Gets the import declaration.
     *
     * @return the import declaration
     */
    public ImportDeclaration getImport() {
        return imp;
    }

    /**
     * Checks if the import is external (resolved to a compiled class).
     *
     * @return true if external, false otherwise
     */
    public boolean isExternal() {
        return clazz != null;
    }

    /**
     * Gets the name of the import as a string.
     *
     * @return the name
     */
    public String getNameAsString() {
        return imp.getNameAsString();
    }

    /**
     * Sets the resolved TypeDeclaration for this import.
     *
     * @param type the type declaration
     */
    public void setType(TypeDeclaration<?> type) {
        this.type = type;
    }

    /**
     * Gets the resolved TypeDeclaration.
     *
     * @return the type declaration
     */
    @SuppressWarnings("java:S1452")
    public TypeDeclaration<?> getType() {
        return type;
    }

    /**
     * Sets the resolved FieldDeclaration for this import (e.g. static import).
     *
     * @param fieldDeclaration the field declaration
     */
    public void setField(FieldDeclaration fieldDeclaration) {
        this.fieldDeclaration = fieldDeclaration;
    }

    /**
     * Gets the resolved FieldDeclaration.
     *
     * @return the field declaration
     */
    public FieldDeclaration getField() {
        return fieldDeclaration;
    }

    /**
     * Gets the resolved MethodDeclaration for this import (e.g. static import).
     *
     * @return the method declaration
     */
    public MethodDeclaration getMethodDeclaration() {
        return methodDeclaration;
    }

    /**
     * Sets the resolved MethodDeclaration.
     *
     * @param methodDeclaration the method declaration
     */
    public void setMethodDeclaration(MethodDeclaration methodDeclaration) {
        this.methodDeclaration = methodDeclaration;
    }

    /**
     * Sets the simplified import declaration (for wildcard resolution).
     *
     * @param decl the simplified import declaration
     */
    public void setSimplified(ImportDeclaration decl) {
        this.simplified = decl;
    }

    /**
     * Gets the simplified import declaration.
     *
     * @return the simplified import declaration
     */
    public ImportDeclaration getSimplified() {
        return simplified;
    }

    /**
     * Returns the string representation of the import declaration.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        if (imp != null) {
            return imp.toString();
        }
        return super.toString();
    }
}
