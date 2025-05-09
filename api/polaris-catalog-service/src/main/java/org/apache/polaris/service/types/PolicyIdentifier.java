/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import java.util.Objects;
import org.apache.iceberg.catalog.Namespace;

/**
 * Represents a modified version of the PolicyIdentifier that is different from the one generated by
 * the OpenAPI generator
 *
 * <p>the open api generation inlines the namespace definition, generates a {@code List<String>}
 * directly, instead of generating a Namespace class. This version uses {@link
 * org.apache.iceberg.catalog.Namespace} for namespace field.
 *
 * <p>TODO: make code generation use {@link org.apache.iceberg.catalog.Namespace} directly
 */
public class PolicyIdentifier {

  private final Namespace namespace;
  private final String name;

  /** Reference to one or more levels of a namespace */
  @ApiModelProperty(
      example = "[\"accounting\",\"tax\"]",
      required = true,
      value = "Reference to one or more levels of a namespace")
  @JsonProperty(value = "namespace", required = true)
  public Namespace getNamespace() {
    return namespace;
  }

  /** */
  @ApiModelProperty(required = true, value = "")
  @JsonProperty(value = "name", required = true)
  public String getName() {
    return name;
  }

  @JsonCreator
  public PolicyIdentifier(
      @JsonProperty(value = "namespace", required = true) Namespace namespace,
      @JsonProperty(value = "name", required = true) String name) {
    this.namespace = Objects.requireNonNullElse(namespace, Namespace.empty());
    this.name = name;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(Namespace namespace, String name) {
    return new Builder(namespace, name);
  }

  public static final class Builder {
    private Namespace namespace;
    private String name;

    private Builder() {}

    private Builder(Namespace namespace, String name) {
      this.namespace = Objects.requireNonNullElse(namespace, Namespace.empty());
      this.name = name;
    }

    public Builder setNamespace(Namespace namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    public PolicyIdentifier build() {
      PolicyIdentifier inst = new PolicyIdentifier(namespace, name);
      return inst;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PolicyIdentifier policyIdentifier = (PolicyIdentifier) o;
    return Objects.equals(this.namespace, policyIdentifier.namespace)
        && Objects.equals(this.name, policyIdentifier.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, name);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PolicyIdentifier {\n");

    sb.append("    namespace: ").append(toIndentedString(namespace)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}
