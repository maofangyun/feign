package example.mfy;

import feign.Feign;
import feign.Logger;
import feign.Retryer;

/**
 * @author maofangyun
 * @date 2021/11/27 9:34
 */
public abstract class FeignClientFactory {
    public static <T> T create(Class<T> clazz) {
        return Feign.builder()
                .logger(new Logger.ErrorLogger()).logLevel(Logger.Level.FULL) // 输出日志到控制台
                .retryer(Retryer.NEVER_RETRY) // 关闭重试
                .decode404() // 把404也解码 -> 这样就不会以异常形式抛出,中断程序喽,方便测试
                .target(clazz, "http://8.136.246.112:8080");
    }
}
