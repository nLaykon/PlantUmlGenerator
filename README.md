# PlantUML Generator

A **Java-based tool** to generate **PlantUML .puml diagrams** from your Java project. Automatically detects classes, fields, methods, inheritance, implementations, and usage relationships.

![Java](https://img.shields.io/badge/Java-17-blue) ![Gradle](https://img.shields.io/badge/Gradle-7.0-brightgreen)

---

## Features

- Parses your Java source files.
- Detects:
    - Classes and interfaces
    - Fields and methods
    - Inheritance (extends) and interface implementation (implements)
    - Class usage relationships
- Outputs a .puml file compatible with [PlantUML](https://plantuml.com/) for diagram generation.

---

## Installation

Clone the repository and build the project using Gradle:

```bash
git clone <repo_url>
cd PlantUmlGenerator
```
`./gradlew clean installDist`


This will generate the executable in:

```text
build\install\PlantUmlGenedrator\bin\PlantUmlGenedrator.bat
```

---

## Usage

Run the generator from the **project root**:

```bash
build\install\PlantUmlGenedrator\bin\PlantUmlGenedrator.bat "{packageDir}" "{savePath}\diagram.puml"
```

- {packageDir} – Path to your Java source folder (e.g., src\main\java\org\example)
- {savePath} – Path to save the generated .puml file (e.g., output\diagram.puml)

### Example

Generate a diagram from src\main\java\org\example and save it to output\diagram.puml:

```bash
build\install\PlantUmlGenedrator\bin\PlantUmlGenedrator.bat "src\main\java\org\example" "output\diagram.puml"
```

Then, generate the UML diagram using PlantUML:

```bash
plantuml output\diagram.puml
```

## Dependencies

- [JavaParser](https://javaparser.org/) – Parsing Java source code
- [JUnit 5](https://junit.org/junit5/) – Testing (optional)

---

## License

MIT License – see [LICENSE](LICENSE) for details.
