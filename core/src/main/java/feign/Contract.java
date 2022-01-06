/**
 * Copyright 2012-2021 The Feign Authors
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

import feign.Request.HttpMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 */
public interface Contract {

  /**
   * 此方法来解析类中链接到HTTP请求的方法:提取有效信息转换成元信息返回
   * MethodMetadata:方法各种元信息,包括但不限于
   * 1.返回值类型returnType
   * 2.请求参数、请求参数的index、名称
   * 3.url、查询参数、请求body体等等等等
   * */
  List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

  abstract class BaseContract implements Contract {

    /**
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @see #parseAndValidateMetadata(Class)
     */
    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
      // 类上不能存在任何一个泛型变量
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s", targetType.getSimpleName());
      // 接口最多最多只能有一个父接口
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s", targetType.getSimpleName());
      final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      // 对该类所有的方法进行解析:包装成一个MethodMetadata
      // getMethods():表示本类+父类的public方法
      for (final Method method : targetType.getMethods()) {
        // 排除掉Object的方法、static方法、default方法等
        if (method.getDeclaringClass() == Object.class || (method.getModifiers() & Modifier.STATIC) != 0 || Util.isDefault(method)) {
          continue;
        }
        // 解析类上的注解信息,方法上的注解信息和参数上的注解信息,封装成元数据MethodMetadata
        final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        if (result.containsKey(metadata.configKey())) {
          MethodMetadata existingMetadata = result.get(metadata.configKey());
          Type existingReturnType = existingMetadata.returnType();
          Type overridingReturnType = metadata.returnType();
          Type resolvedType = Types.resolveReturnType(existingReturnType, overridingReturnType);
          if (resolvedType.equals(overridingReturnType)) {
            result.put(metadata.configKey(), metadata);
          }
          continue;
        }
        result.put(metadata.configKey(), metadata);
      }
      // 注意这里返回的是克隆result
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      // MethodMetadata是非常重要的数据结构,封装了远程调用方法的元信息
      final MethodMetadata data = new MethodMetadata();
      data.targetType(targetType);
      data.method(method);
      // 方法返回类型是支持泛型的
      data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
      // 这里使用了Feign的一个工具方法,来生成configKey,不用过于了解细节,简单的说就是尽量唯一
      data.configKey(Feign.configKey(targetType, method));
      if (AlwaysEncodeBodyContract.class.isAssignableFrom(this.getClass())) {
        data.alwaysEncodeBody(true);
      }

      // 这一步很重要:处理接口上的注解,并且处理了父接口
      // 这就是为何你父接口上的注解,子接口里也生效的原因
      // processAnnotationOnClass()是个abstract方法,交给子类去实现（毕竟注解是可以扩展的）
      if (targetType.getInterfaces().length == 1) {
        // 处理接口类上的@Headers注解
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      // 处理实现类上的@Headers注解
      processAnnotationOnClass(data, targetType);

      // 处理标注在方法上的所有注解
      // 若子接口override了父接口的方法,注解以子接口的为主,忽略父接口方法
      for (final Annotation methodAnnotation : method.getAnnotations()) {
        // 处理方法上的注解,例如@RequestLine、@body和@Headers注解等
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      if (data.isIgnored()) {
        return data;
      }
      // 检查注解上的请求类型,必须不为空(GET/PUT/POST/DELETE等)
      checkState(data.template().method() != null, "Method %s not annotated with HTTP method type (ex. GET, POST)%s", data.configKey(), data.warnings());
      // 获取方法的入参类型
      final Class<?>[] parameterTypes = method.getParameterTypes();
      // 方法参数,支持泛型类型的,例如List<String>
      final Type[] genericParameterTypes = method.getGenericParameterTypes();
      // 注解是个二维数组(参数前可以添加多个注解,所以是二维数组),第一维是参数的索引位置index,第二维是参数的注解
      final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      // count表示入参参数的数量
      final int count = parameterAnnotations.length;
      // 遍历每个参数,处理每个参数上的全部注解
      for (int i = 0; i < count; i++) {
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          // 解析参数上的注解,例如@QueryMap、@HeaderMap和@Param注解等
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }

        if (isHttpAnnotation) {
          data.ignoreParamater(i);
        }
        // 方法参数若存在URI类型的参数,那url就以它为准,不使用全局
        if (parameterTypes[i] == URI.class) {
          data.urlIndex(i);
        } else if (!isHttpAnnotation && parameterTypes[i] != Request.Options.class) {
          // 校验body
          if (data.isAlreadyProcessed(i)) {
            checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                "Body parameters cannot be used with form parameters.%s", data.warnings());
          } else if (!data.alwaysEncodeBody()) {  // 只有@body注解或者任何注解都没有的参数,才能进入此逻辑判断
            checkState(data.formParams().isEmpty(), "Body parameters cannot be used with form parameters.%s", data.warnings());
            checkState(data.bodyIndex() == null, "Method has too many Body parameters: %s%s", method, data.warnings());
            data.bodyIndex(i);
            data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
          }
        }
      }

      if (data.headerMapIndex() != null) {
        checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()], genericParameterTypes[data.headerMapIndex()]);
      }

      if (data.queryMapIndex() != null) {
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }

      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type), "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        final Type[] interfaces = ((Class<?>) genericType).getGenericInterfaces();
        for (final Type extended : interfaces) {
          if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
            // use the first extended interface we find.
            final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
            keyClass = (Class<?>) parameterTypes[0];
            break;
          }
        }
      }

      if (keyClass != null) {
        checkState(String.class.equals(keyClass), "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }

    /**
     * 原生支持注解：@Headers
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * 原生支持注解：@RequestLine、@Body、@Headers
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * 原生支持注解：@Param、@QueryMap、@HeaderMap等
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      final Collection<String> names = data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }

  class Default extends DeclarativeContract {

    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");
    public Default() {
      // 处理类上的@Headers注解
      super.registerClassAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnType = header.value();
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            data.configKey());
        final Map<String, Collection<String>> headers = toMap(headersOnType);
        headers.putAll(data.template().headers());
        data.template().headers(null); // to clear
        data.template().headers(headers);
      });
      // 处理方法上的@RequestLine注解
      super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
        final String requestLine = ann.value();
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", data.configKey());

        final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              data.configKey()));
        } else {
          // 填充请求的类型:GET/POST/PUT/DELETE等类型
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          // 填充url地址,创建UriTemplate对象
          data.template().uri(requestLineMatcher.group(2));
        }
        data.template().decodeSlash(ann.decodeSlash());
        data.template().collectionFormat(ann.collectionFormat());
      });
      // 处理方法上的@Body注解
      super.registerMethodAnnotation(Body.class, (ann, data) -> {
        final String body = ann.value();
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.", data.configKey());
        if (body.indexOf('{') == -1) {  // 非Json信息,直接转换成字节数组保存到RequestTemplate对象的Body域中
          data.template().body(body);
        } else {                        // Json信息,转换成BodyTemplate,在真正调用的时候进行解析
          data.template().bodyTemplate(body);
        }
      });
      // 处理方法上的@Headers注解
      super.registerMethodAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnMethod = header.value();
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            data.configKey());
        data.template().headers(toMap(headersOnMethod));
      });
      // 处理参数上的@Param注解
      super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
        final String annotationName = paramAnnotation.value();
        final Parameter parameter = data.method().getParameters()[paramIndex];
        final String name;
        if (emptyToNull(annotationName) == null && parameter.isNamePresent()) {
          name = parameter.getName();
        } else {
          name = annotationName;
        }
        checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.", paramIndex);
        // 将入参的名称缓存到indexToName属性中
        nameParam(data, name, paramIndex);
        // Expander:调用其expand()方法,将匹配到的value值,写入到模板中
        final Class<? extends Param.Expander> expander = paramAnnotation.expander();
        if (expander != Param.ToStringExpander.class) {
          // 若@Param配置了其他的Expander类型,缓存到indexToExpanderClass中
          data.indexToExpanderClass().put(paramIndex, expander);
        }
        // 判断url中是否有@Param注解标注的入参名称,例如@RequestLine("POST /repos/{owner}/{repo}/issues")
        // 自定义的入参对象,只要没有出现在url中(@RequestLine("POST /repos/{owner}/{repo}/issues")),都算表单参数
        if (!data.template().hasRequestVariable(name)) {
          data.formParams().add(name);
        }
      });
      // 处理参数上的@QueryMap注解
      super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.queryMapIndex() == null,
            "QueryMap annotation was present on multiple parameters.");
        data.queryMapIndex(paramIndex);
        data.queryMapEncoded(queryMap.encoded());
      });
      // 处理参数上的@HeaderMap注解
      super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.headerMapIndex() == null,
            "HeaderMap annotation was present on multiple parameters.");
        data.headerMapIndex(paramIndex);
      });
    }

    private static Map<String, Collection<String>> toMap(String[] input) {
      final Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (final String header : input) {
        final int colon = header.indexOf(':');
        final String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }
  }
}
