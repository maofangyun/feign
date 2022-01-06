package example.mfy;

import com.alibaba.fastjson.JSON;
import lombok.Data;

/**
 * @author maofangyun
 * @date 2021/11/27 15:06
 */
@Data
public class Person {
    private String name = "YourBatman";
    private Integer age = 18;

    /**
     * Feign的默认序列化方式是调用ToString方法,toString方法返回的并不是一个Json格式,所以必须要重写toString()
     * */
    @Override
    public String toString() {
        String s = JSON.toJSONString(this);
        return s;
    }
}
