package github.jaewookmun.mybatis.dsl.assist;


import com.squareup.javapoet.*;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.dynamic.sql.AliasableSqlTable;
import org.mybatis.dynamic.sql.BasicColumn;
import org.mybatis.dynamic.sql.SqlBuilder;
import org.mybatis.dynamic.sql.SqlColumn;
import org.mybatis.dynamic.sql.delete.DeleteDSLCompleter;
import org.mybatis.dynamic.sql.select.SelectDSLCompleter;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.mybatis.dynamic.sql.update.UpdateDSLCompleter;
import org.mybatis.dynamic.sql.util.SqlProviderAdapter;
import org.mybatis.dynamic.sql.util.mybatis3.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.sql.JDBCType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("github.jaewookmun.mybatis.dsl.assist.DynamicModel")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class DynamicModelProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;

    private static final String DYNAMIC_SQL_SUPPORT = "DynamicSqlSupport";

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
            String tableFieldName = generateDynamicSqlSupport(typeElement);
            generateDefaultMapperInterface(typeElement, tableFieldName);
        }

        return true;
    }

    private void generateDefaultMapperInterface(TypeElement element, String tableFieldName) {
        String packageName = elementUtils.getPackageOf(element).getQualifiedName().toString();
        String entityModelName = element.getSimpleName().toString();

        // 1. Default Mapper interface
        TypeSpec.Builder defaultMapper = TypeSpec.interfaceBuilder(entityModelName + "MyBatisDSLMapper")
                .addSuperinterface(CommonCountMapper.class)
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(CommonInsertMapper.class),
                        ClassName.get(element)
                ))
                .addSuperinterface(CommonUpdateMapper.class)
                .addSuperinterface(CommonDeleteMapper.class)
                .addModifiers(Modifier.PUBLIC);

        // 2. BasicColumn[] selectlist 필드 생성
        FieldSpec selectListField = FieldSpec.builder(
                        ArrayTypeName.of(ClassName.get(BasicColumn.class)),
                        "selectList",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("BasicColumn.columnList($L)", generateColumnList(element, entityModelName))
                .build();

        defaultMapper.addField(selectListField);

        // 3. default methods - CRUD (create, read, update, delete)

        /*
            default int insert(PersonRecord row) {
                return MyBatis3Utils.insert(this::insert, row, person, c -> c
                            .map(PersonDynamicSqlSupport.id).toProperty("id")
                            .map(PersonDynamicSqlSupport.firstName).toProperty("firstName")
                            .map(PersonDynamicSqlSupport.lastName).toProperty("lastName")
                            .map(PersonDynamicSqlSupport.birthDate).toProperty("birthDate")
                            .map(PersonDynamicSqlSupport.employed).toProperty("employed")
                            .map(PersonDynamicSqlSupport.occupation).toProperty("occupation")
                            .map(PersonDynamicSqlSupport.addressId).toProperty("addressId")
                );
            }
         */
        MethodSpec insertMethod = MethodSpec.methodBuilder("insert")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(TypeName.INT)
                .addParameter(ClassName.get(element), "row")
                .addCode("return $T.insert(this::insert, row, $T.$L, c -> c\n",
                        ClassName.get(MyBatis3Utils.class),
                        ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                        tableFieldName)
                .addCode(generateInsertMapping(element, entityModelName))
                .addCode(");\n")
                .build();

        defaultMapper.addMethod(insertMethod);

        List<MethodSpec> selectMethodList = generateSelectMethods(element, entityModelName, tableFieldName);
        selectMethodList.forEach(defaultMapper::addMethod);

        // id가 존재할 경우 - TODO: 추후 어노테이션 활용으로 교체
        if (element.getEnclosedElements().stream().anyMatch(e -> e.getSimpleName().toString().equals("id")))
        {
            /*
                @Deprecated
                default int update(UpdateDSLCompleter completer) {
                    return MyBatis3Utils.update(this::update, person, completer);
                }

                default int updateByPrimaryKey(PersonRecord row) {
                    return update(c -> c
                            .set(firstName).equalToWhenPresent(row::getFirstName)
                            .set(lastName).equalToWhenPresent(row::getLastName)
                            .set(birthDate).equalToWhenPresent(row::getBirthDate)
                            .set(employed).equalToWhenPresent(row::getEmployed)
                            .set(occupation).equalToWhenPresent(row::getOccupation)
                            .set(addressId).equalToWhenPresent(row::getAddressId)
                            .where(id, SqlBuilder.isEqualTo(row::getId))
                    );
                }
             */

            MethodSpec deprecatedUpdate = MethodSpec.methodBuilder("update")
                    .addAnnotation(AnnotationSpec.builder(Deprecated.class).build())
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(TypeName.INT)
                    .addParameter(UpdateDSLCompleter.class, "completer")
                    .addCode("return $T.update(this::update, $T.$L, completer);\n",
                            ClassName.get(MyBatis3Utils.class),
                            ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                            tableFieldName)
                    .build();
            defaultMapper.addMethod(deprecatedUpdate);

            String recordParamName = "row";
            MethodSpec updateByPrimaryKey = MethodSpec.methodBuilder("updateByPrimaryKey")
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(TypeName.INT)
                    .addParameter(ClassName.get(element), recordParamName)
                    .addCode("return update(c -> c\n")
                    .addCode(generateUpdateMapping(element, entityModelName, recordParamName))
                    .addCode(");\n")
                    .build();
            defaultMapper.addMethod(updateByPrimaryKey);

            /*
                @Deprecated
                default int delete(DeleteDSLCompleter completer) {
                    return MyBatis3Utils.deleteFrom(this::delete, person, completer);
                }

                default int deleteByPrimaryKey(Integer recordId) {
                    return delete(c -> c.where(id, SqlBuilder.isEqualTo(recordId)));
                }
             */
            MethodSpec deprecatedDelete = MethodSpec.methodBuilder("delete")
                    .addAnnotation(AnnotationSpec.builder(Deprecated.class).build())
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(TypeName.INT)
                    .addParameter(DeleteDSLCompleter.class, "completer")
                    .addCode("return $T.deleteFrom(this::delete, $T.$L, completer);\n",
                            ClassName.get(MyBatis3Utils.class),
                            ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                            tableFieldName)
                    .build();
            defaultMapper.addMethod(deprecatedDelete);

            MethodSpec deleteByPrimaryKey = MethodSpec.methodBuilder("deleteByPrimaryKey")
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(TypeName.INT)
                    .addParameter(Integer.class, "recordId")
                    .addCode("return delete(c -> c.where($T.$L, $T.isEqualTo(recordId)));\n",
                            ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                            "id",
                            ClassName.get(SqlBuilder.class))
                    .build();
            defaultMapper.addMethod(deleteByPrimaryKey);
        }

        JavaFile javaFile = JavaFile.builder(packageName, defaultMapper.build()).build();

        try {
            javaFile.writeTo(filer);

        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "failed to create DefaultMapper file: " + e.getMessage());
        }
    }

    private CodeBlock generateUpdateMapping(TypeElement classElement, String entityModelName, String row)
    {
        CodeBlock.Builder builder = CodeBlock.builder();

        for (Element field : classElement.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD || field.getModifiers().contains(Modifier.STATIC)) continue;

            String fieldName = field.getSimpleName().toString();

            if (fieldName.equals("id")) continue;
            builder.add(".set($T.$L).equalToWhenPresent(" + row + "::get" + toPascalCase(fieldName) + ")",
                    ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                    fieldName);
        }
        builder.add(".where($T.$L, $T.isEqualTo(" + row + "::get" + toPascalCase("id") + "))",
                ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                "id",
                ClassName.get(SqlBuilder.class));

        return builder.build();
    }

    private List<MethodSpec> generateSelectMethods(TypeElement element, String entityModelName, String tableFieldName) {
        List<MethodSpec> selectMethodList = new ArrayList<>();

        String resultMapId = entityModelName + "Result";
        AnnotationSpec resultMap = generateResultMap(element, resultMapId);

        // selectMany method
        /*
            @SelectProvider(type=SqlProviderAdapter.class, method="select")
            @Results(id="PersonResult", value= {
                    @Result(column="id", property="id", jdbcType=JdbcType.INTEGER, id=true),
                    @Result(column="first_name", property="firstName", jdbcType=JdbcType.VARCHAR),
                    @Result(column="last_name", property="lastName", jdbcType=JdbcType.VARCHAR),
                    @Result(column="birth_date", property="birthDate", jdbcType=JdbcType.DATE),
                    @Result(column="employed", property="employed", jdbcType=JdbcType.VARCHAR),
                    @Result(column="occupation", property="occupation", jdbcType=JdbcType.VARCHAR)
            })
            List<PersonRecord> selectMany(SelectStatementProvider selectStatement);
         */
        MethodSpec selectMany = MethodSpec.methodBuilder("selectMany")
                .addAnnotation(AnnotationSpec.builder(SelectProvider.class)
                        .addMember("type", "$T.class", SqlProviderAdapter.class)
                        .addMember("method", "$S", "select")
                        .build()
                )
                .addAnnotation(resultMap)
                .addAnnotation(AnnotationSpec.builder(Deprecated.class).build())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(List.class),
                        ClassName.get(element)
                ))
                .addParameter(SelectStatementProvider.class, "selectStatement")
                .build();

        selectMethodList.add(selectMany);

        // selectOne method
        /*
            @SelectProvider(type=SqlProviderAdapter.class, method="select")
            @ResultMap("PersonResult")
            Optional<PersonRecord> selectOne(SelectStatementProvider selectStatement);
         */
        MethodSpec selectOne = MethodSpec.methodBuilder("selectOne")
                .addAnnotation(AnnotationSpec.builder(SelectProvider.class)
                        .addMember("type", "$T.class", SqlProviderAdapter.class)
                        .addMember("method", "$S", "select")
                        .build())
                .addAnnotation(AnnotationSpec.builder(ResultMap.class)
                        .addMember("value", "$S", resultMapId)
                        .build())
                .addAnnotation(AnnotationSpec.builder(Deprecated.class).build())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(Optional.class),
                        ClassName.get(element)
                ))
                .addParameter(SelectStatementProvider.class, "selectStatement")
                .build();

        selectMethodList.add(selectOne);

        /*
            default Optional<PersonRecord> selectOne(SelectDSLCompleter completer) {
                return MyBatis3Utils.selectOne(this::selectOne, selectList, person, completer);
            }
         */
        MethodSpec secondSelectOne = MethodSpec.methodBuilder("selectOne")
                .addAnnotation(AnnotationSpec.builder(Deprecated.class).build())
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(Optional.class),
                        ClassName.get(element)
                ))
                .addParameter(SelectDSLCompleter.class, "completer")
                .addCode("return $T.selectOne(this::selectOne, selectList, $T.$L, completer);\n",
                        ClassName.get(MyBatis3Utils.class),
                        ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                        tableFieldName)
                .build();

        selectMethodList.add(secondSelectOne);

        // id가 존재할 경우 - TODO: 추후 어노테이션 활용으로 교체
        if (element.getEnclosedElements().stream().anyMatch(e -> e.getSimpleName().toString().equals("id"))) {

            /*
                default Optional<PersonRecord> findById(Integer recordId) {
                    return selectOne(c -> c.where(PersonDynamicSqlSupport.id, SqlBuilder.isEqualTo(recordId)));
                }
             */
            MethodSpec findById = MethodSpec.methodBuilder("findById")
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(ParameterizedTypeName.get(
                            ClassName.get(Optional.class),
                            ClassName.get(element)
                    ))
                    .addParameter(Integer.class, "entityId")
                    .addCode("return selectOne(c -> c.where($L, $T.isEqualTo(entityId)));\n",
                            entityModelName + DYNAMIC_SQL_SUPPORT + ".id",
                            ClassName.get(SqlBuilder.class)
                            )
                    .build();

            selectMethodList.add(findById);
        }

        // second selectMany
        /*
            default List<PersonRecord> selectMany(SelectDSLCompleter completer) {
                return MyBatis3Utils.selectList(this::selectMany, selectList, person, completer);
            }
         */
        MethodSpec secondSelectMany = MethodSpec.methodBuilder("selectMany")
                .addAnnotation(AnnotationSpec.builder(Deprecated.class).build())
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(List.class),
                        ClassName.get(element)
                ))
                .addParameter(SelectDSLCompleter.class, "completer")
                .addCode("return $T.selectList(this::selectMany, selectList, $T.$L, completer);\n",
                        ClassName.get(MyBatis3Utils.class),
                        ClassName.get("", entityModelName + DYNAMIC_SQL_SUPPORT),
                        tableFieldName)
                .build();

        selectMethodList.add(secondSelectMany);


        /*
            default List<PersonRecord> findAll() {
                return selectMany(SelectDSLCompleter.allRows());
            }
         */
        MethodSpec findAll = MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(List.class),
                        ClassName.get(element)
                ))
                .addCode("return selectMany($T.allRows());\n",
                        ClassName.get(SelectDSLCompleter.class))
                .build();

        selectMethodList.add(findAll);

        return selectMethodList;
    }

    private AnnotationSpec generateResultMap(TypeElement element, String resultMapId) {
        AnnotationSpec.Builder resultMap = AnnotationSpec.builder(Results.class)
                .addMember("id", "$S", resultMapId);

        for (Element field : element.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD || field.getModifiers().contains(Modifier.STATIC)) continue;

            String fieldName = field.getSimpleName().toString();
            AnnotationSpec.Builder fieldMapper = AnnotationSpec.builder(Result.class)
                    .addMember("column", "$S", camelToSnakeCase(fieldName))
                    .addMember("property", "$S", fieldName)
                    .addMember("jdbcType", "$T.$L", JdbcType.class, getJdbcType(TypeName.get(field.asType())));

            // TODO: create @Id annotation
            if (fieldName.equals("id")) {
                fieldMapper.addMember("id", "true");
            }

            resultMap.addMember("value", "$L", fieldMapper.build());
        }

        return resultMap.build();
    }

    private CodeBlock generateInsertMapping(TypeElement classElement, String entityModelName) {
        CodeBlock.Builder builder = CodeBlock.builder();

        for (Element field : classElement.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD || field.getModifiers().contains(Modifier.STATIC)) continue;

            String fieldName = field.getSimpleName().toString();
            builder.add(".map($L).toProperty($S)\n",
                    entityModelName + DYNAMIC_SQL_SUPPORT + "." + fieldName,
                    fieldName);
        }

        return builder.build();
    }

    private String generateColumnList(TypeElement classElement, String entityModelName) {
        return classElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD)
                .map(e -> entityModelName + DYNAMIC_SQL_SUPPORT + "." + e.getSimpleName().toString())
                .collect(Collectors.joining(", "));
    }

    private String generateDynamicSqlSupport(TypeElement element) {
        String packageName = elementUtils.getPackageOf(element).getQualifiedName().toString();
        String entityModelName = element.getSimpleName().toString();

        DynamicModel modelAnnotation = element.getAnnotation(DynamicModel.class);
        String tableName = modelAnnotation.table().isEmpty() ? getTableNameFrom(entityModelName) : modelAnnotation.table();

        // 1. SqlSupport
        TypeSpec.Builder dynamicSqlSupport = TypeSpec.classBuilder(entityModelName + DYNAMIC_SQL_SUPPORT).addModifiers(Modifier.PUBLIC);

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

        // 3. 테이블 인스턴스 필드 생성
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

        return modelInstanceName;
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
                .initializer("column($S, $T.$L)",
                        columnName,
                        JDBCType.class,
                        getJdbcType(fieldType)
                )
                .build();

        classBuilder.addField(columnField);
    }

    private String getJdbcType(TypeName typeName) {
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

    private String toPascalCase(String className) {
        return Character.toUpperCase(className.charAt(0)) + className.substring(1);
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
