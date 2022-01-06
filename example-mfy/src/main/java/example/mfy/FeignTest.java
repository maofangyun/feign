package example.mfy;

/**
 * @author maofangyun
 * @date 2021/11/27 9:35
 */

import feign.CollectionFormat;
import feign.template.BodyTemplate;
import feign.template.QueryTemplate;
import feign.template.UriTemplate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class FeignTest {

    /**
     * @RequestLine注解测试
     * */
    @Test
    public void fun1() {
        RequestLineClient client = FeignClientFactory.create(RequestLineClient.class);
        client.testRequestLine("YourBatman1");
        System.err.println(" ------------------ ");
        client.testRequestLine2("YourBatman2");
        System.err.println(" ------------------ ");

        // 使用Map一次传多个请求参数
        Map<String, Object> map = new HashMap<>();
        map.put("name", "YourBatman3");
        map.put("age", Arrays.asList(16, 18, 20));
        client.testRequestLine3(map);
        System.err.println(" ------------------ ");

        try {
            client.testRequestLine4("YourBatman4");
        } catch (Exception e) {
        }
        System.err.println(" ------------------ ");

        try {
            client.testRequestLine5("YourBatman4");
        } catch (Exception e) {
        }

        System.err.println(" ------------------ ");

        try {
            client.testRequestLine8("YourBatman4", 18);
        } catch (Exception e) {
        }
    }

    /**
     * @Param注解测试
     * */
    @Test
    public void fun2() {
        ParamClient client = FeignClientFactory.create(ParamClient.class);
        // http://8.136.246.112:8080/feign/demo1?name=%5BLjava.lang.String%3B%404ef37659
        // 因为调用的是数组的toString()方法,所以入参拼接的是数组的内存地址
        client.testParam(new String[]{"YourBatman", "fsx"});
        System.err.println(" ------------------ ");
        // http://8.136.246.112:8080/feign/demo1?name=1&name=2&name=3
        // TODO 集合能正确的被解析成多值,如何处理的未知
        client.testParam2(Arrays.asList("1", "2", "3"));
        System.err.println(" ------------------ ");
        // http://8.136.246.112:8080/feign/demo1?name=/%3FYourBatman/
        client.testParam3("/?YourBatman/");
        System.err.println(" ------------------ ");
    }

    /**
     * @Body注解测试
     * */
    @Test
    public void fun3() {
        BodyClient client = FeignClientFactory.create(BodyClient.class);
        client.testBody(new Person());
        System.err.println(" ------------------ ");
        client.testBody1();
        System.err.println(" ------------------ ");
        client.testBody2("my name is YourBatman");
        System.err.println(" ------------------ ");
        client.testBody3(new Person());
    }

    /**
     * 用于处理@RequestLine的模版
     * */
    @Test
    public void fun4() {
        UriTemplate template = UriTemplate.create("http://example.com/{foo}", StandardCharsets.UTF_8);
        Map<String, Object> params = new HashMap<>();
        params.put("foo", "bar");
        String result = template.expand(params);
        // http://example.com/bar
        System.out.println(result);

        // 对斜杠不要转义
        template = UriTemplate.create("http://example.com/{empty}{foo}index.html{frag}",false, StandardCharsets.UTF_8);
        params.clear();
        // 为null的参数,直接忽略
        params.put("empty",null);
        params.put("foo","houses/");
        params.put("frag","?g=sec1.2");
        result = template.expand(params);
        // http://example.com/houses/index.html%3Fg=sec1.2
        // /没被转义,但?被转义为了%3
        System.out.println(result);
    }

    /**
     * 用于处理@QueryMap的模版
     * */
    @Test
    public void fun5() {
        // 可以看到key也是可以使用模版的。当然你也可以直接使用字符串即可，也可以混合使用
        QueryTemplate template = QueryTemplate.create("hobby-{arg}", Arrays.asList("basket", "foot"), StandardCharsets.UTF_8);
        Map<String, Object> params = new HashMap<>();
        params.put("arg", "1");
        String result = template.expand(params);
        // %7B代表"{",%7D代表"}"   不填参数arg,此模板会原样的显示arg,不做任何处理  hobby-%7Barg%7D=basket&hobby-%7Barg%7D=foot
        // 加上arg参数, hobby-1=basket&hobby-1=foot
        System.out.println(result);

        template = QueryTemplate.create("grade", Arrays.asList("1", "2"), StandardCharsets.UTF_8, CollectionFormat.CSV);
        // %2C代表","  grade=1%2C2
        // 加上了CollectionFormat.CSV,就会变成grade=1,2  而不是grade=1&grade=2
        System.out.println(template.expand(new HashMap<>()));
    }

    /**
     * 用于处理@Body注解的模版
     * */
    @Test
    public void fun6(){
        BodyTemplate template = BodyTemplate.create("data:{body}");
        Map<String, Object> params = new HashMap<>();
        params.put("body", "{\"name\": \"YourBatman\",\"age\": 18}");
        String result = template.expand(params);
        System.out.println(result);
    }

    @Test
    public void fun7(){
        final GitHub github = GitHub.connect();

        System.out.println("Let's fetch and print a list of the contributors to this org.");
        final List<String> contributors = github.contributors("openfeign");
        for (final String contributor : contributors) {
            System.out.println(contributor);
        }

        System.out.println("Now, let's cause an error.");
        try {
            github.contributors("openfeign", "some-unknown-project");
        } catch (final GitHub.GitHubClientError e) {
            System.out.println(e.getMessage());
        }

        System.out.println("Now, try to create an issue - which will also cause an error.");
        try {
            final GitHub.Issue issue = new GitHub.Issue();
            issue.title = "The title";
            issue.body = "Some Text";
            github.createIssue(issue, "OpenFeign", "SomeRepo");
        } catch (final GitHub.GitHubClientError e) {
            System.out.println(e.getMessage());
        }
    }

}

