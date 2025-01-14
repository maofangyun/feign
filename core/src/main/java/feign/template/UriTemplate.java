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

import java.nio.charset.Charset;

/**
 * 它是用于处理URI的模版，用于处理@RequestLine的模版
 */
public class UriTemplate extends Template {

  /**
   * Create a Uri Template.
   *
   * @param template representing the uri.
   * @param charset for encoding.
   * @return a new Uri Template instance.
   */
  public static UriTemplate create(String template, Charset charset) {
    return new UriTemplate(template, true, charset);
  }

  /**
   * Create a Uri Template.
   *
   * @param template representing the uri
   * @param encodeSlash flag if slash characters should be encoded.
   * @param charset for the template.
   * @return a new Uri Template instance.
   */
  public static UriTemplate create(String template, boolean encodeSlash, Charset charset) {
    return new UriTemplate(template, encodeSlash, charset);
  }

  /**
   * Append a uri fragment to the template.
   *
   * @param uriTemplate to append to.
   * @param fragment to append.
   * @return a new UriTemplate with the fragment appended.
   */
  public static UriTemplate append(UriTemplate uriTemplate, String fragment) {
    return new UriTemplate(uriTemplate.toString() + fragment, uriTemplate.encodeSlash(),
        uriTemplate.getCharset());
  }

  /**
   * Create a new Uri Template.
   *
   * @param template for the uri.
   * @param encodeSlash flag for encoding slash characters.
   * @param charset to use when encoding.
   */
  private UriTemplate(String template, boolean encodeSlash, Charset charset) {
    super(template, ExpansionOptions.REQUIRED, EncodingOptions.REQUIRED, encodeSlash, charset);
  }
}
