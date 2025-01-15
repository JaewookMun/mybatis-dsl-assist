package github.jaewookmun.mybatis.dsl.assist;


import com.squareup.javapoet.*;
import org.mybatis.dynamic.sql.AliasableSqlTable;
import org.mybatis.dynamic.sql.SqlColumn;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.sql.JDBCType;
import java.time.LocalDateTime;
import java.util.Set;

@SupportedAnnotationTypes("github.jaewookmun.mybatis.dsl.assist.DynamicModel")
public class DynamicModelProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(DynamicModel.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;

            TypeElement typeElement = (TypeElement) element;
            generateDynamicSqlSupport(typeElement);
        }

        return true;
    }

    private void generateDynamicSqlSupport(TypeElement element) {
        String packageName = elementUtils.getPackageOf(element).getQualifiedName().toString();
        String entityModelName = element.getSimpleName().toString();

        DynamicModel modelAnnotation = element.getAnnotation(DynamicModel.class);
        String tableName = modelAnnotation.table().isEmpty() ? getTableNameFrom(entityModelName) : modelAnnotation.table();

        // 1. SqlSupport
        TypeSpec.Builder dynamicSqlSupport = TypeSpec.classBuilder(entityModelName + "DynamicSqlSupport").addModifiers(Modifier.PUBLIC);

        // 2. inner class
        TypeSpec.Builder tableModelClassBuilder = TypeSpec.classBuilder(entityModelName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(
                        ClassName.get(AliasableSqlTable.class),
                        ClassName.get("", entityModelName)
                ))
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("super($S, $L::new)", tableName, entityModelName)
                        .build()
                );

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosed;

                if (!field.getModifiers().contains(Modifier.STATIC)) {
                    String fieldName = field.getSimpleName().toString();
                    String columnName = camelToSnakeCase(fieldName);
                    addFieldsToClassBuilder(tableModelClassBuilder, field, columnName);
                }
            }
        }

        dynamicSqlSupport.addType(tableModelClassBuilder
                .build());

        // 테이블 인스턴스 필드 생성
        String modelInstanceName = toCamelCase(entityModelName);
        FieldSpec modelInstance =
                FieldSpec.builder(
                        ClassName.get("", entityModelName),
                        modelInstanceName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL
                )
                .initializer("new $T()", ClassName.get("", entityModelName))
                .build();
        dynamicSqlSupport.addField(modelInstance);

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosed;

                if (!field.getModifiers().contains(Modifier.STATIC)) {
                    String fieldName = field.getSimpleName().toString();
                    TypeName fieldType = TypeName.get(field.asType());

                    TypeName columnType = ParameterizedTypeName.get(
                            ClassName.get(SqlColumn.class),
                            getWrappedType(fieldType)
                    );


                    FieldSpec columnField = FieldSpec.builder(
                                    columnType,
                                    fieldName,
                                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL
                            )
                            .initializer("$N.$N", modelInstanceName, fieldName)
                            .build();

                    dynamicSqlSupport.addField(columnField);
                }
            }
        }

        JavaFile javaFile = JavaFile.builder(packageName, dynamicSqlSupport.build()).build();

        try {
            javaFile.writeTo(filer);

        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "failed to create DynamicSqlSupport file: " + e.getMessage());
        }

    }

    private void addFieldsToClassBuilder(TypeSpec.Builder classBuilder, Element field, String columnName) {
        TypeName fieldType = TypeName.get(field.asType());

        TypeName columnType = ParameterizedTypeName.get(
                ClassName.get(SqlColumn.class),
                getWrappedType(fieldType)
        );

        FieldSpec columnField = FieldSpec.builder(
                        columnType,
                        field.getSimpleName().toString(),
                        Modifier.PUBLIC, Modifier.FINAL
                )
                .initializer("column($S, $N)",
                        columnName,
                        JDBCType.class,
                        getJDBCType(fieldType)
                )
                .build();

        classBuilder.addField(columnField);
    }

    private String getJDBCType(TypeName typeName) {
        if (typeName.equals(TypeName.get(String.class))) return "VARCHAR";
        if (typeName.equals(TypeName.INT) || typeName.equals(ClassName.get(Integer.class))) return "INTEGER";
        if (typeName.equals(TypeName.get(LocalDateTime.class))) return "TIMESTAMP";
        if (typeName.equals(TypeName.BOOLEAN) || typeName.equals(ClassName.get(Boolean.class))) return "BOOLEAN";
        if (typeName.equals(TypeName.LONG) || typeName.equals(ClassName.get(Long.class))) return "BIGINT";
        if (typeName.equals(TypeName.DOUBLE) || typeName.equals(ClassName.get(Double.class))) return "DOUBLE";
        if (typeName.equals(TypeName.FLOAT) || typeName.equals(ClassName.get(Float.class))) return "FLOAT";

        return "VARCHAR";
    }

    /**
     * primitive type to reference type
     *
     * @param typeName primitive type
     * @return wrapper class name
     */
    private TypeName getWrappedType(TypeName typeName) {
        if (typeName.equals(TypeName.INT)) return ClassName.get(Integer.class);
        if (typeName.equals(TypeName.BOOLEAN)) return ClassName.get(Boolean.class);
        if (typeName.equals(TypeName.LONG)) return ClassName.get(Long.class);
        if (typeName.equals(TypeName.DOUBLE)) return ClassName.get(Double.class);
        if (typeName.equals(TypeName.FLOAT)) return ClassName.get(Float.class);

        return typeName;
    }

    /**
     *
     * @param fieldName camelCase string
     * @return column name
     */
    protected String camelToSnakeCase(String fieldName) {
        String regex = "([a-z])([A-Z])";
        String replacement = "$1_$2";
        return fieldName.replaceAll(regex, replacement).toLowerCase();
    }

    /**
     * change from Pascal Case to Camel Case
     * ex. DynamicModelProcessor -> dynamicModelProcessor
     *
     * @param className Pascal Case String
     * @return camelCase string
     */
    private String toCamelCase(String className) {

        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    protected String getTableNameFrom(String className) {
        StringBuilder sb = new StringBuilder();

        sb.append(Character.toLowerCase(className.charAt(0)));

        for (int i = 1; i < className.length(); i++) {
            char currentChar = className.charAt(i);

            if (Character.isUpperCase(currentChar)) {
                sb.append("_");
                sb.append(Character.toLowerCase(currentChar));
            } else sb.append(currentChar);
        }

        return sb.toString();
    }
}
