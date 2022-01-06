package example.mfy;

import feign.Body;
import feign.Headers;
import feign.Param;
import feign.RequestLine;

/**
 * @author maofangyun
 * @date 2021/11/27 15:08
 */
public interface BodyClient {

    // url会被编码成/feign/demo/%7B%22age%22%3A18%2C%22name%22%3A%22YourBatman%22%7D
    // 同时body域会被填充"test"的字节数组
    @Body("test")
    @RequestLine("POST /feign/demo/{person}")
    String testBody(@Param("person") Person person);

    // 1、@Body里可以是写死的字符串
    @Body("{\"name\" : \"YourBatman\"}")
    @RequestLine("POST /feign/demo1")
    String testBody1();

    // 2、@Body可以使用模版{} 取值
    @Body("{body}")
    @RequestLine("POST /feign/demo2")
    String testBody2(@Param("body") String name);

    // 3、@Body里取值来自于一个JavaBean
    @Body("{person}")
    @RequestLine("POST /feign/demo3")
    @Headers({"content-type:application/json"}) // @Headers来指定请求头,不然报415错误,Unsupported Media Type
    String testBody3(@Param("person") Person person);
}
