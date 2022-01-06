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

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Cloned for each invocation to {@link Client#execute(Request, feign.Request.Options)}.
 * Implementations may keep state to determine if retry operations should continue or not.
 */
public interface Retryer extends Cloneable {

  /**
   * if retry is permitted, return (possibly after sleeping). Otherwise propagate the exception.
   */
  void continueOrPropagate(RetryableException e);

  Retryer clone();

  /**
   * Feign默认使用的是Default这个实例,所以Feign是有重试机制的,并且是100ms重试一次,默认重试5次.
   * */
  class Default implements Retryer {
    // 重试参数
    private final int maxAttempts;  // 最大重试次数
    private final long period;      // 重试周期,隔多久重试一次
    private final long maxPeriod;   // 最大重试时间
    // 内部统计指标计数
    int attempt;  //计数:重试了几次
    long sleptForMillis;  // 休息时间

    // 空构造,默认重试参数
    // period:默认100ms重试一次
    // maxPeriod:最大重试1秒钟
    // maxAttempts:最多重试5次
    public Default() {
      this(100, SECONDS.toMillis(1), 5);
    }

    public Default(long period, long maxPeriod, int maxAttempts) {
      this.period = period;
      this.maxPeriod = maxPeriod;
      this.maxAttempts = maxAttempts;
      this.attempt = 1;
    }

    // visible for testing;
    protected long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    /**
     * 如果允许重试,则return返回(可能在睡眠后),
     * 否则会把这个异常继续传播
     * */
    public void continueOrPropagate(RetryableException e) {
      // 1. 若超过了最大重试次数,异常继续向上抛出
      // 2. 设置不重试,异常继续向上抛出
      if (attempt++ >= maxAttempts) {
        throw e;
      }

      long interval;
      if (e.retryAfter() != null) {
        interval = e.retryAfter().getTime() - currentTimeMillis();
        if (interval > maxPeriod) {
          interval = maxPeriod;
        }
        if (interval < 0) {
          return;
        }
      } else {
        interval = nextMaxInterval();
      }
      try {
        Thread.sleep(interval);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        throw e;
      }
      sleptForMillis += interval;
    }

    /**
     * Calculates the time interval to a retry attempt. <br>
     * The interval increases exponentially with each attempt, at a rate of nextInterval *= 1.5
     * (where 1.5 is the backoff factor), to the maximum interval.
     *
     * @return time in milliseconds from now until the next attempt.
     */
    long nextMaxInterval() {
      long interval = (long) (period * Math.pow(1.5, attempt - 1));
      return interval > maxPeriod ? maxPeriod : interval;
    }

    @Override
    public Retryer clone() {
      return new Default(period, maxPeriod, maxAttempts);
    }
  }

  /**
   * Implementation that never retries request. It propagates the RetryableException.
   * 永不重试实例,
   * 生产环境下:建议使用永不重试,否则请做好幂等
   */
  Retryer NEVER_RETRY = new Retryer() {

    @Override
    public void continueOrPropagate(RetryableException e) {
      throw e;
    }

    @Override
    public Retryer clone() {
      return this;
    }
  };
}
