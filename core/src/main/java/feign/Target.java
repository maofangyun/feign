/**
 * Copyright 2012-2020 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static feign.Util.checkNotNull;
import static feign.Util.emptyToNull;

/**
 * <br>
 * <br>
 * <b>relationship to JAXRS 2.0</b><br>
 * <br>
 * Similar to {@code
 * javax.ws.rs.client.WebTarget}, as it produces requests. However, {@link RequestTemplate} is a
 * closer match to {@code WebTarget}.
 *
 * @param <T> type of the interface this target applies to.
 */
public interface Target<T> {

  // 此target作用的接口的类型
  Class<T> type();

  // 此target配置的一个key,一般情况下没啥用
  // 但会在和feign-hystrix整合时,会作为它的groupKey来使用,这也是它的唯一被使用的地方
  String name();

  // 发送请求的BaseURL,如https://example/api/v1
  String url();

  /**
   * 最重要的方法:用于把请求模版组装,加上BaseUrl,转换为一个真正的Request
   * 此input模版里包含有很多的:QueryTemplate,HeaderTemplate,UriTemplate等
   */
  Request apply(RequestTemplate input);

  public static class HardCodedTarget<T> implements Target<T> {

    private final Class<T> type;
    private final String name;
    // 相较于EmptyTarget,它必须有url
    private final String url;

    public HardCodedTarget(Class<T> type, String url) {
      this(type, url, url);
    }

    public HardCodedTarget(Class<T> type, String name, String url) {
      this.type = checkNotNull(type, "type");
      this.name = checkNotNull(emptyToNull(name), "name");
      this.url = checkNotNull(emptyToNull(url), "url");
    }

    @Override
    public Class<T> type() {
      return type;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String url() {
      return url;
    }

    @Override
    public Request apply(RequestTemplate input) {
      // 若请求模版的URL不含有http,也就是说是个相对路径,这里就把Target.url加进去;
      // 若请求模版已经是绝对路径了,那就不管
      if (input.url().indexOf("http") != 0) {
        input.target(url());
      }
      return input.request();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof HardCodedTarget) {
        HardCodedTarget<?> other = (HardCodedTarget) obj;
        return type.equals(other.type)
            && name.equals(other.name)
            && url.equals(other.url);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = 17;
      result = 31 * result + type.hashCode();
      result = 31 * result + name.hashCode();
      result = 31 * result + url.hashCode();
      return result;
    }

    @Override
    public String toString() {
      if (name.equals(url)) {
        return "HardCodedTarget(type=" + type.getSimpleName() + ", url=" + url + ")";
      }
      return "HardCodedTarget(type=" + type.getSimpleName() + ", name=" + name + ", url=" + url
          + ")";
    }
  }

  public static final class EmptyTarget<T> implements Target<T> {

    private final Class<T> type;
    private final String name;

    /**
     * url必须为空
     * */
    EmptyTarget(Class<T> type, String name) {
      this.type = checkNotNull(type, "type");
      this.name = checkNotNull(emptyToNull(name), "name");
    }

    public static <T> EmptyTarget<T> create(Class<T> type) {
      return new EmptyTarget<T>(type, "empty:" + type.getSimpleName());
    }

    public static <T> EmptyTarget<T> create(Class<T> type, String name) {
      return new EmptyTarget<T>(type, name);
    }

    @Override
    public Class<T> type() {
      return type;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String url() {
      throw new UnsupportedOperationException("Empty targets don't have URLs");
    }

    @Override
    public Request apply(RequestTemplate input) {
      // 如果请求模版的URL里已经包含了http,即绝对路径,EmptyTarget才能支持,
      // 否则直接抛出异常
      if (input.url().indexOf("http") != 0) {
        throw new UnsupportedOperationException(
            "Request with non-absolute URL not supported with empty target");
      }
      return input.request();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof EmptyTarget) {
        EmptyTarget<?> other = (EmptyTarget) obj;
        return type.equals(other.type)
            && name.equals(other.name);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = 17;
      result = 31 * result + type.hashCode();
      result = 31 * result + name.hashCode();
      return result;
    }

    @Override
    public String toString() {
      if (name.equals("empty:" + type.getSimpleName())) {
        return "EmptyTarget(type=" + type.getSimpleName() + ")";
      }
      return "EmptyTarget(type=" + type.getSimpleName() + ", name=" + name + ")";
    }
  }
}
