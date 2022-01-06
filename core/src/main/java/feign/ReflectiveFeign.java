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

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.*;
import feign.template.UriUtils;

public class ReflectiveFeign extends Feign {

  private final ParseHandlersByName targetToHandlersByName;
  private final InvocationHandlerFactory factory;
  private final QueryMapEncoder queryMapEncoder;

  ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
      QueryMapEncoder queryMapEncoder) {
    this.targetToHandlersByName = targetToHandlersByName;
    this.factory = factory;
    this.queryMapEncoder = queryMapEncoder;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T newInstance(Target<T> target) {
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
    List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

    for (Method method : target.type().getMethods()) {
      if (method.getDeclaringClass() == Object.class) {
        continue;
      } else if (Util.isDefault(method)) {
        // 用来执行非远程调用的方法,即本地方法
        DefaultMethodHandler handler = new DefaultMethodHandler(method);
        defaultMethodHandlers.add(handler);
        methodToHandler.put(method, handler);
      } else {
        // 将Map<String, MethodHandler>的映射关系转换成Map<Method, MethodHandler>的映射关系,即Method和SynchronousMethodHandler的映射关系
        methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
      }
    }
    InvocationHandler handler = factory.create(target, methodToHandler);
    // 创建了一个JDK的动态代理类
    T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(), new Class<?>[] {target.type()}, handler);

    for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
      // 将此本地方法绑定代理对象,使得远程方法和本地方法的调用对于客户端透明
      defaultMethodHandler.bindTo(proxy);
    }
    return proxy;
  }

  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Map<Method, MethodHandler> dispatch;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }

    /**
     * 对方法完成正调度的核心,是所有方法调用的入口
     * */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      // 省略对equals、hashCode、toString等方法的处理代码
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      }
      // 通过dispath完成调度,因此最终调用的是MethodHandler#invoke
      return dispatch.get(method).invoke(args);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FeignInvocationHandler) {
        FeignInvocationHandler other = (FeignInvocationHandler) obj;
        return target.equals(other.target);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }

  static final class ParseHandlersByName {

    private final Contract contract;
    private final Options options;
    private final Encoder encoder;
    private final Decoder decoder;
    private final ErrorDecoder errorDecoder;
    private final QueryMapEncoder queryMapEncoder;
    private final SynchronousMethodHandler.Factory factory;

    ParseHandlersByName(
        Contract contract,
        Options options,
        Encoder encoder,
        Decoder decoder,
        QueryMapEncoder queryMapEncoder,
        ErrorDecoder errorDecoder,
        SynchronousMethodHandler.Factory factory) {
      this.contract = contract;
      this.options = options;
      this.factory = factory;
      this.errorDecoder = errorDecoder;
      this.queryMapEncoder = queryMapEncoder;
      this.encoder = checkNotNull(encoder, "encoder");
      this.decoder = checkNotNull(decoder, "decoder");
    }

    public Map<String, MethodHandler> apply(Target target) {
      // 解析Class对象中的方法,并封装成元数据MethodMetadata的集合
      List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
      Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
      for (MethodMetadata md : metadata) {
        BuildTemplateByResolvingArgs buildTemplate;
        if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {     // 表单类型的(有@Param注解,但是没有在url的{}中体现)
          buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
        } else if (md.bodyIndex() != null || md.alwaysEncodeBody()) {                 // body类型的
          buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
        } else {                                                                      // 普通类型的
          buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
        }
        if (md.isIgnored()) {
          result.put(md.configKey(), args -> { throw new IllegalStateException(md.configKey() + " is not a method handled by feign");});
        } else {
          // 为每一个方法创建一个SynchronousMethodHandler
          result.put(md.configKey(), factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
        }
      }
      return result;
    }
  }

  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

    private final QueryMapEncoder queryMapEncoder;

    protected final MethodMetadata metadata;
    protected final Target<?> target;
    // key:数字类型,表示参数的index
    // Expander:feign.Param.Expander,也就是由注解决定使用哪种Expander,默认是ToStringExpander
    private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

    // 唯一构造器:给前三个属性赋值,并且根据这三个属性,计算出indexToExpander的值并缓存
    private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder, Target target) {
      this.metadata = metadata;
      this.target = target;
      this.queryMapEncoder = queryMapEncoder;
      if (metadata.indexToExpander() != null) {
        indexToExpander.putAll(metadata.indexToExpander());
        return;
      }
      if (metadata.indexToExpanderClass().isEmpty()) {
        return;
      }
      for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata.indexToExpanderClass().entrySet()) {
        try {
          indexToExpander.put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
        } catch (InstantiationException e) {
          throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    /**
     * 解析元数据对象MethodMetadata,创建RequestTemplate并将对应参数填进RequestTemplate各个属性里
     * */
    @Override
    public RequestTemplate create(Object[] argv) {
      // 新建出来一份RequestTemplate
      RequestTemplate mutable = RequestTemplate.from(metadata.template());
      mutable.feignTarget(target);
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.target(String.valueOf(argv[urlIndex]));
      }
      // 将入参的名称和入参的值结合起来
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        int i = entry.getKey();
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          if (indexToExpander.containsKey(i)) {
            value = expandElements(indexToExpander.get(i), value);
          }
          for (String name : entry.getValue()) {
            // 入参的键值对映射关系
            varBuilder.put(name, value);
          }
        }
      }
      // 使用入参填充url,形成最终能直接访问的url
      RequestTemplate template = resolve(argv, mutable, varBuilder);
      // 支持@QueryMap
      if (metadata.queryMapIndex() != null) {
        // add query map parameters after initial resolve so that they take
        // precedence over any predefined values
        Object value = argv[metadata.queryMapIndex()];
        Map<String, Object> queryMap = toQueryMap(value);
        template = addQueryMapQueryParameters(queryMap, template);
      }
      // 支持@HeaderMap
      if (metadata.headerMapIndex() != null) {
        template = addHeaderMapHeaders((Map<String, Object>) argv[metadata.headerMapIndex()], template);
      }

      return template;
    }

    private Map<String, Object> toQueryMap(Object value) {
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      try {
        return queryMapEncoder.encode(value);
      } catch (EncodeException e) {
        throw new IllegalStateException(e);
      }
    }

    private Object expandElements(Expander expander, Object value) {
      if (value instanceof Iterable) {
        return expandIterable(expander, (Iterable) value);
      }
      return expander.expand(value);
    }

    private List<String> expandIterable(Expander expander, Iterable value) {
      List<String> values = new ArrayList<String>();
      for (Object element : value) {
        if (element != null) {
          values.add(expander.expand(element));
        }
      }
      return values;
    }

    @SuppressWarnings("unchecked")
    private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : headerMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : nextObject.toString());
          }
        } else {
          values.add(currValue == null ? null : currValue.toString());
        }

        mutable.header(currEntry.getKey(), values);
      }
      return mutable;
    }

    @SuppressWarnings("unchecked")
    private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                       RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : queryMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        boolean encoded = metadata.queryMapEncoded();
        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null
                : encoded ? nextObject.toString()
                    : UriUtils.encode(nextObject.toString()));
          }
        } else if (currValue instanceof Object[]) {
          for (Object value : (Object[]) currValue) {
            values.add(value == null ? null
                : encoded ? value.toString() : UriUtils.encode(value.toString()));
          }
        } else {
          values.add(currValue == null ? null
              : encoded ? currValue.toString() : UriUtils.encode(currValue.toString()));
        }

        mutable.query(encoded ? currEntry.getKey() : UriUtils.encode(currEntry.getKey()), values);
      }
      return mutable;
    }

    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder, QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey())) {
          formVariables.put(entry.getKey(), entry.getValue());
        }
      }
      try {
        // 填充到body域中
        encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {

      boolean alwaysEncodeBody = mutable.methodMetadata().alwaysEncodeBody();

      Object body = null;
      if (!alwaysEncodeBody) {
        body = argv[metadata.bodyIndex()];
        checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      }

      try {
        if (alwaysEncodeBody) {
          body = argv == null ? new Object[0] : argv;
          encoder.encode(body, Object[].class, mutable);
        } else {
          encoder.encode(body, metadata.bodyType(), mutable);
        }
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}
