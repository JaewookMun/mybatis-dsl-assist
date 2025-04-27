# MyBatis DSL Assist

'MyBatis DSL Assist' is designed for MyBatis Dynamic SQL library (https://github.com/mybatis/mybatis-dynamic-sql).

This project helps reduce repetitive and tedious coding tasks through annotations.

## Key Features

- Annotation-based Query Generation: Generate dynamic SQL queries by simply adding annotations to classes
- Automatic Parameter Mapping: Automatically convert method parameters to SQL conditions

## Getting Started

### Requirements

- Java 1.8 or higher
- MyBatis 3.5.13 or higher
- MySQL (or MariaDb) * This project has not been tested in other DB environments like Oracle, PostgreSQL, etc.

### Installation

Since it's not currently uploaded to Maven Central, you need to build a jar file and add the dependency manually.
I recommend building as a fat jar because it won't be able to properly fetch sub-dependencies otherwise.
(I plan to upload it to Maven Central in the future)


**Gradle**
``` text
dependencies {
    ...    
    compileOnly files('libs/mybatis-dsl-assist-1.0-SNAPSHOT-all.jar')
    annotationProcessor files('libs/mybatis-dsl-assist-1.0-SNAPSHOT-all.jar')
}
```
* This requires dependency composition of 'compileOnly' and 'annotationProcessor'.

## Usage Example

### @DynamicModel annotation
```java
@DynamicModel
public class Fruit {
    private String name;
    private String color;
    private String location;
    // ...
}
```
When you declare the @DynamicModel annotation on a domain class you want to use with MyBatis Dynamic SQL,
it automatically generates the DynamicSqlSupport class and Mapper interface during build time.

path example
- domain-path: {project-root-path}/src/main/java/com/github/jaewookmun/mybatistest/domain/Fruit.java 
- gen-path: {project-root-path}/build/generated/sources/annotationProcessor/java/.../

![generated-image](https://github.com/user-attachments/assets/320c0fdf-b2f2-43dc-9f5a-c619f9b3b673)

```java
// auto creation after build task by annotation processing
public class FruitDynamicSqlSupport {
    public static final Fruit fruit = new Fruit();
    public static final SqlColumn<String> name = fruit.name;
    public static final SqlColumn<String> color = fruit.color;
    public static final SqlColumn<String> location = fruit.location;

    public static final class Fruit extends AliasableSqlTable<Fruit> {
        public final SqlColumn<String> name = column("name", JDBCType.VARCHAR);
        public final SqlColumn<String> color = column("color", JDBCType.VARCHAR);
        public final SqlColumn<String> location = column("location", JDBCType.VARCHAR);

        public Fruit() {
            super("fruit", Fruit::new);
        }
    }
}
```

```java
public interface FruitMyBatisDSLMapper extends CommonCountMapper, CommonInsertMapper<Fruit>, CommonUpdateMapper, CommonDeleteMapper {
	BasicColumn[] selectList = BasicColumn.columnList(FruitDynamicSqlSupport.name, FruitDynamicSqlSupport.color, FruitDynamicSqlSupport.location);

	@InsertProvider(type = SqlProviderAdapter.class, method = "insert")
	@Override
	int insert(InsertStatementProvider<Fruit> insertStatement);

	default int insert(Fruit row) {
		return MyBatis3Utils.insert(this::insert, row, FruitDynamicSqlSupport.fruit, c -> c
		.map(FruitDynamicSqlSupport.name).toProperty("name")
		.map(FruitDynamicSqlSupport.color).toProperty("color")
		.map(FruitDynamicSqlSupport.location).toProperty("location")
		);
	}

	@SelectProvider(type = SqlProviderAdapter.class, method = "select")
	@Results(
			id = "FruitResult",
			value = {
					@Result(column = "name", property = "name", jdbcType = JdbcType.VARCHAR),
					@Result(column = "color", property = "color", jdbcType = JdbcType.VARCHAR),
					@Result(column = "location", property = "location", jdbcType = JdbcType.VARCHAR)
			}
	)
	@Deprecated
	List<Fruit> selectMany(SelectStatementProvider selectStatement);

	@SelectProvider(type = SqlProviderAdapter.class, method = "select")
	@ResultMap("FruitResult")
	@Deprecated
	Optional<Fruit> selectOne(SelectStatementProvider selectStatement);

	@Deprecated
	default Optional<Fruit> selectOne(SelectDSLCompleter completer) {
		return MyBatis3Utils.selectOne(this::selectOne, selectList, FruitDynamicSqlSupport.fruit, completer);
	}

	@Deprecated
	default List<Fruit> selectMany(SelectDSLCompleter completer) {
		return MyBatis3Utils.selectList(this::selectMany, selectList, FruitDynamicSqlSupport.fruit, completer);
	}

	default List<Fruit> findAll() {
		return selectMany(SelectDSLCompleter.allRows());
	}

	@Deprecated
	default int update(UpdateDSLCompleter completer) {
		return MyBatis3Utils.update(this::update, FruitDynamicSqlSupport.fruit, completer);
	}

	@Deprecated
	default int delete(DeleteDSLCompleter completer) {
		return MyBatis3Utils.deleteFrom(this::delete, FruitDynamicSqlSupport.fruit, completer);
	}
}
```

<br>

### @Id annotation
When performing read/update/delete operations on domain information stored in the DB, if you only need to perform these operations on a single record based on PK,
you can add the @Id annotation to the field corresponding to the PK.

```java
@DynamicModel
public class Fruit {
    @Id // @Id 어노테이션 추가
    private int id;
    private String name;
    private String color;
    private String location;
    // ...
}
```
When you declare the @Id annotation, the findById, updateById, and deleteById methods are added to the generated ~MyBatisDslMapper, which increases convenience for simple operations.

```java
// ...
default Optional<Fruit> findById(Integer id) {
    return selectOne(c -> c.where(FruitDynamicSqlSupport.id, SqlBuilder.isEqualTo(id)));
}

default int updateById(Fruit row) {
    return update(c -> c
            .set(FruitDynamicSqlSupport.name).equalToWhenPresent(row::getName)
            .set(FruitDynamicSqlSupport.color).equalToWhenPresent(row::getColor)
            .set(FruitDynamicSqlSupport.location).equalToWhenPresent(row::getLocation)
            .where(FruitDynamicSqlSupport.id, SqlBuilder.isEqualTo(row::getId)));
}

default int deleteById(Integer id) {
    return delete(c -> c.where(FruitDynamicSqlSupport.id, SqlBuilder.isEqualTo(id)));
}
```

- notice
When using the useGeneratedKeys option, you can insert a domain object and automatically retrieve and use the generated PK value.

```java
@Id(useGeneratedKeys = true)
private int id;
```