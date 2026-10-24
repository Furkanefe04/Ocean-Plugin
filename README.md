# Ocean IntelliJ Plugin

Ocean IntelliJ Plugin brings editor and code insight support for the [Ocean V3 programming language](https://github.com/Furkanefe04/Ocean) to IntelliJ IDEA. It recognizes `.ocean` files and uses an ANTLR grammar and IntelliJ PSI (Program Structure Interface) to provide language-aware editing, analysis, navigation, and code cleanup.

## Features

### Language support and editor assistance

- `.ocean` file type, lexer, parser, and Ocean color settings
- Syntax highlighting and semantic highlighting for declarations, references, modifiers, annotations, and interpolated strings
- Context-aware completion for Ocean keywords and declarations, project classes, members, imports, and supported Java symbols
- Parameter information and quick documentation for Ocean declarations and methods
- Brace matching, quote handling, Ocean comments, word selection, and statement movement
- Code folding, structure view, and breadcrumbs
- Ocean code style settings and a formatter for spacing around operators, delimiters, braces, and control-flow constructs

The grammar includes classes, interfaces, enums, annotation declarations, data and sealed classes, generics, async/await, ranges, collection literals, lambdas, and interpolated strings. The plugin provides IDE support for Ocean syntax; running a program still requires the Ocean compiler/runtime.

### Semantic analysis and compiler diagnostics

The built-in Ocean annotator resolves references and reports semantic problems in the editor. Its checks include unresolved names, incompatible types and operations, invalid access or static-context use, duplicate and invalid overrides, definite assignment, return and exception rules, and other Ocean language constraints.

A separate external annotator can run the Ocean compiler and display its JSON diagnostics in the editor. It invokes the configured `ocean` executable with the current file and compiler diagnostic options. The compiler must be installed and available to the plugin for this feature; see [Run and analyze Ocean files](#run-and-analyze-ocean-files).

### Inspections and quick fixes

The Ocean inspection group includes:

- **Compiler and semantic errors** reported by the Ocean annotator during code inspection
- **Unused symbol** warnings for unused local variables, parameters, loop variables, catch variables, and pattern variables
- **Unused import** warnings with a quick fix to remove the import
- **Unreachable statement** warnings for statements after terminating control flow, with a quick fix to delete the unreachable code

Other available quick fixes and intentions include creating a missing method or field, initializing a variable, adding a return statement, making a function async, removing an invalid override or method body, and renaming an unused binding to `_` where applicable.

### Formatting and import cleanup

Use IntelliJ's **Reformat Code** action to apply Ocean spacing and code-style rules. This formats code layout; it does not rewrite program logic or optimize runtime performance.

Use **Optimize Imports** to remove unused non-wildcard imports, remove duplicate imports, and sort the remaining imports into groups: static imports, `java.*`/`javax.*`, other libraries, then `ocean.*`. Wildcard imports are kept.

### Navigation, refactoring, and code generation

- Go to declaration, reference resolution, find usages, and search for Ocean classes and symbols
- Rename Ocean declarations in place
- Introduce variable, extract method, and inline variable refactorings
- Implement interface methods and override inherited Ocean or Java methods
- Generate constructors, getters and setters, and `equals()`/`hashCode()` methods
- Create Ocean files from templates for classes, interfaces, enums, annotations, and data classes

### Running Ocean programs

- Ocean run configurations for selecting and running an `.ocean` file
- A run gutter action for a `main` function
- Line-breakpoint placement on eligible executable lines in Ocean files
- Console links for compiler output where source locations can be identified

## Requirements

- IntelliJ IDEA 2023.3 or later (the plugin declares IntelliJ Platform build `233` as its minimum)
- JDK 17 to build the plugin
- Ocean compiler/runtime to run Ocean programs or use compiler-backed diagnostics

The Ocean compiler is a separate project. Install it from the [Ocean V3 repository](https://github.com/Furkanefe04/Ocean) before using run configurations or compiler-backed analysis.

## Build and run the plugin

Clone or open this repository, then run the Gradle wrapper from the project root:

```shell
./gradlew runIde
```

On Windows:

```bat
gradlew.bat runIde
```

This launches a development instance of IntelliJ IDEA with the plugin enabled.

Build a distributable plugin ZIP with:

```shell
./gradlew buildPlugin
```

On Windows:

```bat
gradlew.bat buildPlugin
```

The ZIP is created in `build/distributions/`. In IntelliJ IDEA, install it from **Settings | Plugins | ⚙ | Install Plugin from Disk...**.

## Run and analyze Ocean files

1. Install the Ocean compiler/runtime separately.
2. Set the Ocean home directory in IntelliJ's **Settings | Languages & Frameworks | Ocean** page, or set the `OCEAN_HOME` environment variable.
3. The plugin searches the configured directory for `ocean`, `ocean.bat`, or `ocean.cmd`, including those under a `bin` directory. If no home directory is configured, it looks for `ocean` on `PATH` (`ocean.bat` on Windows).
4. Open an `.ocean` file. To run it, create an **Ocean** run configuration and choose the file; use the gutter action beside `main` when available.

Compiler-backed diagnostics invoke the Ocean executable with JSON reporting options. They depend on the compiler being installed and runnable from the configured location or `PATH`.

## Development

- `src/main/antlr/Ocean.g4` — Ocean language grammar
- `src/generated/java/ocean/plugin/` — generated ANTLR lexer, parser, listener, and visitor sources
- `src/main/java/ocean/plugin/` — IntelliJ language integration, analysis, editing, navigation, and run support
- `src/main/resources/META-INF/plugin.xml` — plugin metadata and IntelliJ extension registrations
- `src/test/java/ocean/plugin/` — plugin verification tests

Run the test suite with:

```shell
./gradlew test
```

On Windows:

```bat
gradlew.bat test
```

## License

This project is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.
