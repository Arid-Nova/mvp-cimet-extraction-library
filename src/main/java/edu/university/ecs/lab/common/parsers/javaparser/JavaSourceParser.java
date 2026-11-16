package edu.university.ecs.lab.common.parsers.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.SourceFileReader;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.json.JSONObject;

/**
 * One file's immutable parser context. No parser configuration, solver or AST is shared with
 * another file or request. IR members are read from their declaring type, never from a
 * compilation-unit-wide findAll traversal.
 */
public final class JavaSourceParser {
  private final CompilationUnit unit;
  private final Path sourcePath;
  private final String packageName;
  private final String fileTypeName;

  public JavaSourceParser(File source, RepositoryConfig config) {
    try {
      byte[] bytes = SourceFileReader.read(source.toPath());
      ParserConfiguration syntax =
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
      var solver =
          new CombinedTypeSolver(
              new ReflectionTypeSolver(),
              new JavaParserTypeSolver(
                  Path.of(FileUtils.getRepositoryPath(config.getRepoName())), syntax));
      ParserConfiguration configured =
          new ParserConfiguration()
              .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
              .setSymbolResolver(new JavaSymbolSolver(solver));
      var result = new JavaParser(configured).parse(new String(bytes, StandardCharsets.UTF_8));
      if (!result.isSuccessful() || result.getResult().isEmpty())
        throw new IllegalArgumentException("Invalid Java syntax");
      unit = result.getResult().orElseThrow();
      unit.setStorage(source.toPath().toAbsolutePath());
      sourcePath = Path.of(FileUtils.localPathToGitPath(source.getPath(), config.getRepoName()));
      packageName =
          unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
      fileTypeName = source.getName().replaceFirst("\\.java$", "");
    } catch (Exception failure) {
      // Parser diagnostics can contain source literals; retain only a bounded category.
      String category =
          failure instanceof java.io.IOException
                  && failure.getMessage() != null
                  && failure.getMessage().startsWith("SOURCE_FILE_TOO_LARGE")
              ? "SOURCE_FILE_TOO_LARGE"
              : failure.getClass().getSimpleName();
      throw new IllegalArgumentException("Could not parse Java source (" + category + ")");
    }
  }

  public List<AbstractClass> parse(Microservice service, boolean filterUnknownRoles) {
    List<AbstractClass> classes = new ArrayList<>();
    List<TypeDeclaration<?>> declarations = new ArrayList<>();
    for (TypeDeclaration<?> declaration : unit.findAll(TypeDeclaration.class))
      if (isSupportedMemberType(declaration)) declarations.add(declaration);
    TypeDeclaration<?> primary =
        declarations.stream()
            .filter(type -> type.isTopLevelType() && type.getNameAsString().equals(fileTypeName))
            .findFirst()
            .orElseGet(
                () ->
                    declarations.stream()
                        .filter(TypeDeclaration::isTopLevelType)
                        .findFirst()
                        .orElse(null));
    // Preserve the physical file identity and legacy single-container accessor for its primary
    // type.
    declarations.sort(Comparator.comparingInt(type -> type == primary ? 0 : 1));
    for (TypeDeclaration<?> declaration : declarations) {
      String declaredType = declaredType(declaration);
      Path identity =
          declaration == primary
              ? sourcePath
              : sourcePath.resolveSibling(
                  sourcePath.getFileName() + ".__java_type__." + declaredType + ".java");
      AbstractClass type = createType(service, declaration, identity);
      if (type == null) continue;
      ClassRole role = SpringAnnotationSupport.role(declaration);
      if (filterUnknownRoles && role == ClassRole.UNKNOWN) continue;
      type.setClassRole(role);
      type.setName(declaration.getNameAsString());
      type.setProtection(AccessModifier.fromAccessSpecifier(declaration.getAccessSpecifier()));
      type.setIsFinal(
          declaration.hasModifier(Modifier.Keyword.FINAL)
              || declaration instanceof EnumDeclaration
              || declaration instanceof RecordDeclaration);
      type.setIsStatic(declaration.hasModifier(Modifier.Keyword.STATIC));
      type.setIsAbstract(
          declaration.hasModifier(Modifier.Keyword.ABSTRACT)
              || declaration instanceof ClassOrInterfaceDeclaration named && named.isInterface());
      type.setMetadata(
          new JSONObject()
              .put("language", "java")
              .put("sourcePath", sourcePath.toString().replace('\\', '/'))
              .put("declaredType", declaredType)
              .put(
                  "qualifiedName",
                  packageName.isEmpty() ? declaredType : packageName + "." + declaredType));
      type.setImports(SourceToObjectUtils.parseImports(type, unit.getImports()));
      type.setAnnotations(SourceToObjectUtils.parseAnnotations(type, declaration.getAnnotations()));
      type.setFields(fields(type, declaration));
      type.setMethods(SpringAnnotationSupport.methods(type, declaration));
      classes.add(type);
    }
    return List.copyOf(classes);
  }

  /** Local/anonymous declarations have no stable member-type identity and remain out of scope. */
  private static boolean isSupportedMemberType(TypeDeclaration<?> declaration) {
    if (!(declaration instanceof ClassOrInterfaceDeclaration
        || declaration instanceof EnumDeclaration
        || declaration instanceof RecordDeclaration)) return false;
    var parent = declaration.getParentNode().orElse(null);
    return parent instanceof CompilationUnit
        || parent instanceof TypeDeclaration<?> outer && isSupportedMemberType(outer);
  }

  private static String declaredType(TypeDeclaration<?> declaration) {
    Deque<String> names = new ArrayDeque<>();
    com.github.javaparser.ast.Node current = declaration;
    while (current instanceof TypeDeclaration<?> type) {
      names.addFirst(type.getNameAsString());
      current = current.getParentNode().orElse(null);
    }
    return String.join(".", names);
  }

  private AbstractClass createType(
      Microservice service, TypeDeclaration<?> declaration, Path identity) {
    if (declaration instanceof ClassOrInterfaceDeclaration named) {
      Set<String> parents =
          named.getExtendedTypes().stream()
              .map(type -> type.getNameAsString())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      if (named.isInterface()) return new JInterface(service, identity, packageName, parents);
      Set<String> interfaces =
          named.getImplementedTypes().stream()
              .map(type -> type.getNameAsString())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      return new JClass(
          service, identity, packageName, parents.stream().findFirst().orElse(""), interfaces);
    }
    if (declaration instanceof EnumDeclaration enumeration) {
      Set<String> interfaces =
          enumeration.getImplementedTypes().stream()
              .map(type -> type.getNameAsString())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      Set<String> entries =
          enumeration.getEntries().stream()
              .map(entry -> entry.getNameAsString())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      return new JEnum(service, identity, packageName, interfaces, entries);
    }
    if (declaration instanceof RecordDeclaration record) {
      Set<String> interfaces =
          record.getImplementedTypes().stream()
              .map(type -> type.getNameAsString())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      return new JRecord(service, identity, packageName, interfaces);
    }
    return null;
  }

  private static Set<Field> fields(AbstractClass owner, TypeDeclaration<?> declaration) {
    Set<Field> fields = new LinkedHashSet<>();
    for (BodyDeclaration<?> member : declaration.getMembers()) {
      if (!(member instanceof FieldDeclaration field)) continue;
      for (VariableDeclarator variable : field.getVariables())
        fields.add(new Field(owner, field, variable));
    }
    if (declaration instanceof RecordDeclaration record) {
      for (var parameter : record.getParameters()) {
        VariableDeclarator variable =
            new VariableDeclarator(parameter.getType().clone(), parameter.getNameAsString());
        parameter.getRange().ifPresent(variable::setRange);
        FieldDeclaration field =
            new FieldDeclaration().addVariable(variable).setPrivate(true).setFinal(true);
        fields.add(new Field(owner, field, variable));
      }
    }
    return fields;
  }
}
