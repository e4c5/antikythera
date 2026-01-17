# Review of TypeWrapper Usage and Migration to JavaParser ResolvedType

## 1. Introduction

The `TypeWrapper` class in the Antikythera project currently serves as a bridge between JavaParser's AST-based types (`TypeDeclaration`) and Java's Reflection-based types (`Class`). It is extensively used to represent types that might be defined in the source code being analyzed or in external compiled libraries. The class also includes utility methods for checking specific annotations (e.g., Spring stereotypes like `@Controller`, `@Service`) and handling type compatibility checks (`isAssignableFrom`).

This document reviews the usage of `TypeWrapper` in `AbstractCompiler`, `Resolver`, and `DepSolver`, and outlines a strategy for modernizing it using JavaParser's `ResolvedType` and related classes from the `javaparser-symbol-solver` module.

## 2. Current Usage Analysis

### 2.1 AbstractCompiler
`AbstractCompiler` is the heaviest user of `TypeWrapper`. It uses `TypeWrapper` to:
- **Resolve Types**: Methods like `findType`, `findWrappedTypes`, and `detectTypeWithClassLoaders` return `TypeWrapper`.
- **Hybrid Resolution**: It attempts to find a `TypeDeclaration` in the AST first. If not found, it falls back to loading the class via `ClassLoader` and wrapping the `Class` object.
- **Cache Types**: It caches resolved types wrapped in `TypeWrapper`.

### 2.2 Resolver
`Resolver` relies on `TypeWrapper` indirectly via `AbstractCompiler`.
- **Field Resolution**: `resolveThisFieldAccess` uses `AbstractCompiler.findType` to get a `TypeWrapper` for a field's type.
- **Dependency Graph**: It creates `GraphNode` instances based on the types found in `TypeWrapper`.

### 2.3 DepSolver and GraphNode
`DepSolver` and `GraphNode` use `TypeWrapper` for:
- **Inheritance**: `GraphNode.inherit` uses `AbstractCompiler.findType` to resolve extended types (superclasses) to check for abstract methods.
- **Enum Constants**: `addEnumConstantHelper` resolves constructor arguments using `TypeWrapper`.
- **Type Compatibility**: `TypeWrapper.isAssignableFrom` is used to check if types are compatible, handling mixed scenarios (AST vs. Reflection).

## 3. Proposed Strategy: Evolutionary Encapsulation

Instead of replacing `TypeWrapper` wholesale, the recommended approach is an **Evolutionary Strategy**. This involves refactoring `TypeWrapper` to use `ResolvedType` internally while maintaining its existing API. This allows the project to leverage the power of JavaParser's symbol solver without a high-risk "big bang" refactor.

- **`ResolvedType`**: Represents a resolved type (e.g., `ResolvedReferenceType`, `ResolvedPrimitiveType`). It abstracts away the difference between source and binary types.
- **`ResolvedReferenceTypeDeclaration`**: Represents the declaration of a type (class, interface, enum). It has implementations like `JavaParserClassDeclaration` (source) and `ReflectionClassDeclaration` (binary).
- **`JavaSymbolSolver`**: The engine that performs resolution. `AbstractCompiler` already configures a `CombinedTypeSolver`, so the infrastructure is present.

### 3.1 Concept
`TypeWrapper` will evolve to hold a single source of truth: `ResolvedReferenceTypeDeclaration` (or `ResolvedType`). The legacy fields `TypeDeclaration<?> type` (AST) and `Class<?> clazz` (Reflection) will become derived properties.

```java
public class TypeWrapper {
    // The single source of truth
    private final ResolvedType resolvedType;

    // Computed or lazily loaded on demand
    public String getFullyQualifiedName() {
        return resolvedType.describe();
    }

    public boolean isController() {
        // Check annotations on resolvedType
        return resolvedType.asReferenceType().hasAnnotation("org.springframework.stereotype.Controller") || ...
    }

    public TypeDeclaration<?> getType() {
        // Convert to AST on demand (see section 3.2)
        return toAst(resolvedType).orElse(null);
    }
}
```

### 3.2 Converting ResolvedType to AST

One of the most critical functions of `TypeWrapper` is holding a reference to the `TypeDeclaration` (AST). `ResolvedType` separates the symbol (resolved) from the source (AST). To retrieve the AST node from a `ResolvedType`, the following pattern should be used:

```java
public Optional<TypeDeclaration<?>> toAst(ResolvedType resolvedType) {
    if (resolvedType.isReferenceType()) {
        return resolvedType.asReferenceType()
                .getTypeDeclaration()
                .flatMap(ResolvedReferenceTypeDeclaration::toAst)
                .flatMap(node -> node instanceof TypeDeclaration
                        ? Optional.of((TypeDeclaration<?>) node)
                        : Optional.empty());
    }
    return Optional.empty();
}
```

*Note: `ResolvedReferenceTypeDeclaration.toAst()` returns `Optional<Node>`, so a cast/check is required.*

## 4. Benefits of Evolutionary Strategy

1.  **Risk Reduction**: Preserves existing API contracts, minimizing breaking changes in `AbstractCompiler`, `Resolver`, and `GraphNode`.
2.  **Accuracy**: `ResolvedType` handles generics, type inference, and boxing/unboxing significantly better than the custom logic currently in `TypeWrapper`.
3.  **Extensibility**: Allows for the addition of new capabilities that were previously difficult to implement.

### Examples of New Functionality
By holding the `ResolvedType`, `TypeWrapper` can be enriched with methods like:

```java
// Expose the underlying resolved type for advanced usage
public ResolvedType getResolvedType() {
    return this.resolvedType;
}

// Better type checking using JavaParser's logic
public boolean isAssignableBy(TypeWrapper other) {
    if (this.resolvedType != null && other.resolvedType != null) {
        return this.resolvedType.isAssignableBy(other.resolvedType);
    }
    // Fallback to legacy logic if needed
    return false;
}

// Check for specific annotations easily
public boolean hasAnnotation(String qualifiedName) {
    return this.resolvedType.isReferenceType() &&
           this.resolvedType.asReferenceType().hasAnnotation(qualifiedName);
}
```

## 5. Feasibility
The migration is highly feasible because `AbstractCompiler` already sets up the `JavaSymbolSolver`. The codebase is currently re-implementing much of what `JavaSymbolSolver` does natively. Transitioning to the native solver will reduce code debt and improve reliability.

## 6. Implementation Plan

The migration should be done in phases to minimize disruption.

### Phase 1: Introduction of ResolvedType to TypeWrapper
- Add a `ResolvedType` field to `TypeWrapper`.
- Update constructors or `AbstractCompiler` logic to populate this field using `JavaSymbolSolver` whenever possible.

### Phase 2: Internal Refactoring
- Modify methods like `getFullyQualifiedName()`, `isAssignableFrom()`, and boolean checks (`isController`) to use the `ResolvedType` field if available.
- Implement the "fallback" logic: if `ResolvedType` is null (e.g., resolution failed), continue using the legacy AST/Reflection fields. This ensures 100% backward compatibility during the transition.

### Phase 3: Deprecation of Legacy Fields
- Mark `TypeDeclaration<?> type` and `Class<?> clazz` as deprecated within `TypeWrapper`.
- Change their getters (`getType()`, `getClazz()`) to lazily derive values from `ResolvedType` where possible.

### Phase 4: API Modernization
- Introduce new methods in `AbstractCompiler` that work directly with `ResolvedType`.
- Update call sites (`Resolver`, `DepSolver`) to prefer the new methods (`getResolvedType()`) over legacy accessors.

## 7. Risks and Mitigations

- **Reflection Access**: Some parts of the code specifically need the `java.lang.Class` object (e.g., for instantiation).
    - *Mitigation*: `ResolvedReferenceTypeDeclaration` often allows access to the underlying reflection object if it is reflection-based. For source-based types, the need for `Class` objects should be minimized or handled via specific bridges.
- **Performance**: `JavaSymbolSolver` can be slower than simple name matching.
    - *Mitigation*: Ensure `JavaParserCache` is effectively used. The current `TypeWrapper` logic also performs expensive operations (class loading), so the net performance impact should be neutral or positive due to better caching.
- **Inexact Matching**: `TypeWrapper.isAssignableFrom` currently has some "fuzzy" matching logic.
    - *Mitigation*: Careful testing is required to ensure `ResolvedType.isAssignableBy` covers the necessary cases. If "fuzzy" matching is a requirement, it can be re-implemented as a custom method within the refactored `TypeWrapper`.

## 8. Conclusion
Moving to `ResolvedType` is a necessary step for the project's maturity. The **Evolutionary Strategy**—refactoring `TypeWrapper` to encapsulate `ResolvedType`—offers the safest and most pragmatic path. It allows the project to immediately benefit from robust type resolution while deferring the cost and risk of a system-wide refactoring.
