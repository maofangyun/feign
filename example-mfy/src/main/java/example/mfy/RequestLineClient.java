package example.mfy;

import feign.*;

import java.util.Map;

/**
 * @author maofangyun
 * @date 2021/11/27 9:34
 */
public interface RequestLineClient {

    // 1、正常使用、正常书写
    @Headers({"Accept:*/*", "Accept-Language:    zh-cn"})
    @RequestLine("GET /feign/demo1?name={name}")
    String testRequestLine(@Param("name") String name);

    // 2、GET后不止一个空格，有多个空格
    @RequestLine("GET             /feign/demo1?name={name}")
    String testRequestLine2(@Param("name") String name);

    // 3、使用Map一次性传递多个查询参数，使用注解为@QueryMap
    @RequestLine("GET /feign/demo1")
    String testRequestLine3(@QueryMap Map<String, Object> params);

    // 4、方法参数上不使用任何注解
    @RequestLine("GET /feign/demo1")
    String testRequestLine4(String name);

    // 5、方法上标注有@Body注解，然后把方法参数传递给它
    @RequestLine("GET /feign/demo1")
    @Body("{name}")
    String testRequestLine5(@Param("name") String name);

    // 6、方法两个参数，均不使用注解标注
    // 启动直接报错：Method has too many Body parameters:
    // @RequestLine("GET /feign/demo1")
    // String testRequestLine6(String name,Integer age);

    // 7、启动直接报错：Body parameters cannot be used with form parameters.
//     @RequestLine("GET /feign/demo1")
//     @Body("{age}")
//     String testRequestLine7(@Param("name") String name, Integer age);

    // 8、如果你既想要body参数，又想要查询参数，请这么写
    @RequestLine("GET /feign/demo1?name={name}")
    @Body("{age}")
    String testRequestLine8(@Param("name") String name, @Param("age") Integer age);
}
