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

package org.apache.polaris.core.storage.azure;

import static org.apache.polaris.core.storage.azure.AzureCredentialsStorageIntegration.toAccessConfig;

import java.time.Instant;
import org.apache.polaris.core.storage.AccessConfig;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class AzureCredentialsStorageIntegrationTest {

  @Test
  public void testAzureCredentialFormatting() {
    Instant expiresAt = Instant.ofEpochMilli(Long.MAX_VALUE);

    AccessConfig noSuffixResult = toAccessConfig("sasToken", "some_account", expiresAt);
    Assertions.assertThat(noSuffixResult.credentials()).hasSize(2);
    Assertions.assertThat(noSuffixResult.credentials()).containsKey("adls.sas-token.some_account");

    AccessConfig adlsSuffixResult =
        toAccessConfig("sasToken", "some_account." + AzureLocation.ADLS_ENDPOINT, expiresAt);
    Assertions.assertThat(adlsSuffixResult.credentials()).hasSize(3);
    Assertions.assertThat(adlsSuffixResult.credentials())
        .containsKey("adls.sas-token.some_account");
    Assertions.assertThat(adlsSuffixResult.credentials())
        .containsKey("adls.sas-token.some_account." + AzureLocation.ADLS_ENDPOINT);

    AccessConfig blobSuffixResult =
        toAccessConfig("sasToken", "some_account." + AzureLocation.BLOB_ENDPOINT, expiresAt);
    Assertions.assertThat(blobSuffixResult.credentials()).hasSize(3);
    Assertions.assertThat(blobSuffixResult.credentials())
        .containsKey("adls.sas-token.some_account");
    Assertions.assertThat(blobSuffixResult.credentials())
        .containsKey("adls.sas-token.some_account." + AzureLocation.BLOB_ENDPOINT);
  }
}
