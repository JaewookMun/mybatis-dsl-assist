package github.jaewookmun.mybatis.dsl.assist;

import org.junit.jupiter.api.Test;

class DynamicModelProcessorTest
{
    @Test
    void getTableNameFrom() {
        DynamicModelProcessor dynamicModelProcessor = new DynamicModelProcessor();

        String tableName = dynamicModelProcessor.getTableNameFrom("DynamicModel");

        System.out.println("tableName = " + tableName);


        String name1 = "apple";
        String name2 = "appleJuice";
        String name3 = "appleJuiceLemon";
        String name4 = "Robert";

        String s1 = dynamicModelProcessor.camelToSnakeCase(name1);
        String s2 = dynamicModelProcessor.camelToSnakeCase(name2);
        String s3 = dynamicModelProcessor.camelToSnakeCase(name3);
        String s4 = dynamicModelProcessor.camelToSnakeCase(name4);

        System.out.println(s1);
        System.out.println(s2);
        System.out.println(s3);
        System.out.println(s4);
    }
}