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
package feign.template;

import feign.Util;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Template for @{@link feign.Body} annotated Templates. Unresolved expressions are preserved as
 * literals and literals are not URI encoded.
 * 处理标注@Body注解的模版,对JSON格式进行了兼容处理
 */
public final class BodyTemplate extends Template {

  private static final String JSON_TOKEN_START = "{";
  private static final String JSON_TOKEN_END = "}";
  private static final String JSON_TOKEN_START_ENCODED = "%7B";
  private static final String JSON_TOKEN_END_ENCODED = "%7D";
  private boolean json = false;

  /**
   * Create a new Body Template.
   *
   * @param template to parse.
   * @return a Body Template instance.
   */
  public static BodyTemplate create(String template) {
    return new BodyTemplate(template, Util.UTF_8);
  }

  /**
   * Create a new Body Template.
   *
   * @param template to parse.
   * @param charset to use when encoding the template.
   * @return a Body Template instance.
   */
  public static BodyTemplate create(String template, Charset charset) {
    return new BodyTemplate(template, charset);
  }

  private BodyTemplate(String value, Charset charset) {
    super(value, ExpansionOptions.ALLOW_UNRESOLVED, EncodingOptions.NOT_REQUIRED, false, charset);
    // 判断是否是Json,如果你的模版字符串是以%7B打头%7D结尾的,就标记是JSON,后面填充时会特殊处理
    // 说明:这个自己手动构造构造不出来的,只有通过编码器处理过才有可能这里是true
    if (value.startsWith(JSON_TOKEN_START_ENCODED) && value.endsWith(JSON_TOKEN_END_ENCODED)) {
      this.json = true;
    }
  }

  @Override
  public String expand(Map<String, ?> variables) {
    String expanded = super.expand(variables);
    // 若是JSON,会进行特殊处理
    if (this.json) {
      /* restore all start and end tokens */
      expanded = expanded.replaceAll(JSON_TOKEN_START_ENCODED, JSON_TOKEN_START);
      expanded = expanded.replaceAll(JSON_TOKEN_END_ENCODED, JSON_TOKEN_END);
    }
    return expanded;
  }


}
