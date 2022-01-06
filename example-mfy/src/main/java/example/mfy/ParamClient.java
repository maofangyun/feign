package example.mfy;

import feign.Param;
import feign.RequestLine;

import java.util.Collection;

/**
 * @author maofangyun
 * @date 2021/11/27 14:48
 */
public interface ParamClient {

    // 1、参数为数组类型
    @RequestLine("GET /feign/demo1?name={name}")
    String testParam(@Param("name") String[] names);

    // 2、参数为List类型
    @RequestLine("GET /feign/demo1?name={name}")
    String testParam2(@Param("name") Collection<String> names);

    // 3、参数值包含特殊字符：? / 这种
    @RequestLine("GET /feign/demo1?name={name}")
    String testParam3(@Param("name") String name);
}
