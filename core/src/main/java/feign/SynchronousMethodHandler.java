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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.Util.checkNotNull;

final class SynchronousMethodHandler implements MethodHandler {

  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  private final MethodMetadata metadata;
  // 最终真正构建Http请求Request的实例,一般为HardCodedTarget
  private final Target<?> target;
  // 负责最终请求的发送 -> 默认传进来的是基于JDK源生的,效率很低,不建议直接使用
  private final Client client;
  // 负责重试 -->默认传进来的是Default,是有重试机制的,生产上使用请务必注意
  private final Retryer retryer;
  // 请求拦截器，它会在target.apply(template);也就是模版 -> 请求的转换之前完成拦截
  // 说明:并不是发送请求之前那一刻,请务必注意
  // 它的作用只能是对请求模版做定制,而不能再对Request做定制了
  // 内置仅有一个实现:BasicAuthRequestInterceptor(用于鉴权)
  private final List<RequestInterceptor> requestInterceptors;
  private final Logger logger;
  private final Logger.Level logLevel;
  // 构建请求模版的工厂,对于请求模版,有多种构建方式,内部会用到可能多个编码器
  private final RequestTemplate.Factory buildTemplateFromArgs;
  private final Options options;
  private final ExceptionPropagationPolicy propagationPolicy;

  // 解码器:用于对Response进行解码
  private final Decoder decoder;
  private final AsyncResponseHandler asyncResponseHandler;

  /**
   * 唯一的构造器，并且还是私有的
   * */
  private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer,
      List<RequestInterceptor> requestInterceptors, Logger logger,
      Logger.Level logLevel, MethodMetadata metadata,
      RequestTemplate.Factory buildTemplateFromArgs, Options options,
      Decoder decoder, ErrorDecoder errorDecoder, boolean decode404,
      boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy,
      boolean forceDecoding) {

    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.propagationPolicy = propagationPolicy;

    if (forceDecoding) {
      // internal only: usual handling will be short-circuited, and all responses will be passed to
      // decoder directly!
      this.decoder = decoder;
      this.asyncResponseHandler = null;
    } else {
      this.decoder = null;
      this.asyncResponseHandler = new AsyncResponseHandler(logLevel, logger, decoder, errorDecoder,
          decode404, closeAfterDecode);
    }
  }

  @Override
  public Object invoke(Object[] argv) throws Throwable {
    // 根据方法入参,结合工厂构建出一个请求模版
    // RequestTemplate是聚合有多种模版、参数、值, 转换成标准请求对象feign.Request
    RequestTemplate template = buildTemplateFromArgs.create(argv);
    // 如果你方法入参里含有Options类型这里会被找出来; 说明：若有多个只会有第一个生效(不会报错)
    Options options = findOptions(argv);
    // 重试机制:注意这里是克隆一个来使用,原因是每个请求的重试都要维护一个重试的计数属性attempt,所以必须克隆
    Retryer retryer = this.retryer.clone();
    while (true) {
      try {
        return executeAndDecode(template, options);
      } catch (RetryableException e) {
        try {
          // 执行重试的操作(即重试计数和重试等待),当超过最大重试次数或者设置不重试,会直接将原来的异常(即RetryableException)抛出来
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          Throwable cause = th.getCause();
          if (propagationPolicy == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        if (logLevel != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel);
        }
        continue;
      }
    }
  }

  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    Request request = targetRequest(template);

    if (logLevel != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel, request);
    }

    Response response;
    long start = System.nanoTime();
    try {
      response = client.execute(request, options);
      // ensure the request is set. TODO: remove in Feign 12
      response = response.toBuilder()
          .request(request)
          .requestTemplate(template)
          .build();
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
      }
      // 包装成RetryableException异常抛出
      throw errorExecuting(request, e);
    }
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);


    if (decoder != null)
      return decoder.decode(response, metadata.returnType());
    // 是个假的异步Response处理器,可能是为了后续升级考虑
    CompletableFuture<Object> resultFuture = new CompletableFuture<>();
    asyncResponseHandler.handleResponse(resultFuture, metadata.configKey(), response, metadata.returnType(), elapsedTime);

    try {
      if (!resultFuture.isDone())
        throw new IllegalStateException("Response handling not done");

      return resultFuture.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause != null)
        throw cause;
      throw e;
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  Request targetRequest(RequestTemplate template) {
    // 执行请求拦截器,在Request对象创建之前进行拦截
    for (RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);
    }
    return target.apply(template);
  }

  Options findOptions(Object[] argv) {
    if (argv == null || argv.length == 0) {
      return this.options;
    }
    return Stream.of(argv)
        .filter(Options.class::isInstance)
        .map(Options.class::cast)
        // 若存在多个Option,只返回第一个
        .findFirst()
        .orElse(this.options);
  }

  static class Factory {

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;
    private final boolean forceDecoding;

    Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors,
        Logger logger, Logger.Level logLevel, boolean decode404, boolean closeAfterDecode,
        ExceptionPropagationPolicy propagationPolicy, boolean forceDecoding) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.decode404 = decode404;
      this.closeAfterDecode = closeAfterDecode;
      this.propagationPolicy = propagationPolicy;
      this.forceDecoding = forceDecoding;
    }

    public MethodHandler create(Target<?> target,
                                MethodMetadata md,
                                RequestTemplate.Factory buildTemplateFromArgs,
                                Options options,
                                Decoder decoder,
                                ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger,
          logLevel, md, buildTemplateFromArgs, options, decoder,
          errorDecoder, decode404, closeAfterDecode, propagationPolicy, forceDecoding);
    }
  }
}
