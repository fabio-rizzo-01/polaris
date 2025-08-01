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
package org.apache.polaris.core.persistence;

import static org.apache.polaris.core.entity.PolarisBaseEntity.convertPropertiesToJson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.entity.EntityNameLookupRecord;
import org.apache.polaris.core.entity.PolarisBaseEntity;
import org.apache.polaris.core.entity.PolarisChangeTrackingVersions;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.entity.PolarisEntityCore;
import org.apache.polaris.core.entity.PolarisEntityId;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PolarisGrantRecord;
import org.apache.polaris.core.entity.PolarisPrincipalSecrets;
import org.apache.polaris.core.entity.PolarisPrivilege;
import org.apache.polaris.core.entity.PolarisTaskConstants;
import org.apache.polaris.core.persistence.dao.entity.BaseResult;
import org.apache.polaris.core.persistence.dao.entity.CreateCatalogResult;
import org.apache.polaris.core.persistence.dao.entity.CreatePrincipalResult;
import org.apache.polaris.core.persistence.dao.entity.DropEntityResult;
import org.apache.polaris.core.persistence.dao.entity.EntityResult;
import org.apache.polaris.core.persistence.dao.entity.LoadGrantsResult;
import org.apache.polaris.core.persistence.dao.entity.LoadPolicyMappingsResult;
import org.apache.polaris.core.persistence.dao.entity.PolicyAttachmentResult;
import org.apache.polaris.core.persistence.dao.entity.ResolvedEntityResult;
import org.apache.polaris.core.persistence.pagination.PageToken;
import org.apache.polaris.core.policy.PolarisPolicyMappingRecord;
import org.apache.polaris.core.policy.PolicyEntity;
import org.apache.polaris.core.policy.PolicyType;
import org.apache.polaris.core.policy.PredefinedPolicyTypes;
import org.assertj.core.api.Assertions;

/** Test the Polaris persistence layer */
public class PolarisTestMetaStoreManager {

  // call context
  final PolarisCallContext polarisCallContext;

  // call metastore manager
  final PolarisMetaStoreManager polarisMetaStoreManager;

  // the start time
  private final long testStartTime;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private final boolean supportsChangeTracking;

  // if true, simulate retries by client
  private boolean doRetry;

  // initialize the test
  public PolarisTestMetaStoreManager(
      PolarisMetaStoreManager polarisMetaStoreManager, PolarisCallContext polarisCallContext) {
    this(polarisMetaStoreManager, polarisCallContext, System.currentTimeMillis(), true);

    // bootstrap the Polaris service
    polarisMetaStoreManager.purge(polarisCallContext);
    polarisMetaStoreManager.bootstrapPolarisService(polarisCallContext);
  }

  public PolarisTestMetaStoreManager(
      PolarisMetaStoreManager polarisMetaStoreManager,
      PolarisCallContext polarisCallContext,
      long testStartTime,
      boolean supportsChangeTracking) {
    this.testStartTime = testStartTime;
    this.polarisCallContext = polarisCallContext;
    this.polarisMetaStoreManager = polarisMetaStoreManager;
    this.supportsChangeTracking = supportsChangeTracking;
    this.doRetry = false;
  }

  public void forceRetry() {
    this.doRetry = true;
  }

  /**
   * Validate that the specified identity identified by the pair catalogId, id has been properly
   * persisted.
   *
   * @param catalogPath path of that entity in the catalog. If null, this entity is top-level
   * @param entityId id
   * @param expectedActive true if this entity should be active
   * @param expectedName its expected name
   * @param expectedType its expected type
   * @param expectedSubType its expected subtype
   * @return the persisted entity as a DPO
   */
  private PolarisBaseEntity ensureExistsById(
      List<PolarisEntityCore> catalogPath,
      long entityId,
      boolean expectedActive,
      String expectedName,
      PolarisEntityType expectedType,
      PolarisEntitySubType expectedSubType) {

    // derive id of the catalog for that entity as well as its parent id
    final long catalogId;
    final long parentId;
    if (catalogPath == null) {
      // top-level entity
      catalogId = PolarisEntityConstants.getNullId();
      parentId = PolarisEntityConstants.getRootEntityId();
    } else {
      catalogId = catalogPath.get(0).getId();
      parentId = catalogPath.get(catalogPath.size() - 1).getId();
    }

    // make sure this entity was persisted
    PolarisBaseEntity entity =
        polarisMetaStoreManager
            .loadEntity(this.polarisCallContext, catalogId, entityId, expectedType)
            .getEntity();

    // assert all expected values
    Assertions.assertThat(entity).isNotNull();
    Assertions.assertThat(entity.getName()).isEqualTo(expectedName);
    Assertions.assertThat(entity.getParentId()).isEqualTo(parentId);
    Assertions.assertThat(entity.getTypeCode()).isEqualTo(expectedType.getCode());
    Assertions.assertThat(entity.getSubTypeCode()).isEqualTo(expectedSubType.getCode());

    // ensure creation time set
    Assertions.assertThat(this.testStartTime).isLessThanOrEqualTo(entity.getCreateTimestamp());
    Assertions.assertThat(this.testStartTime).isLessThanOrEqualTo(entity.getLastUpdateTimestamp());

    // test active
    if (expectedActive) {
      // make sure any other timestamps are 0
      Assertions.assertThat(entity.getPurgeTimestamp()).isEqualTo(0);
      Assertions.assertThat(entity.getDropTimestamp()).isEqualTo(0);
      Assertions.assertThat(entity.getPurgeTimestamp()).isEqualTo(0);

      // we should find it
      EntityResult result =
          polarisMetaStoreManager.readEntityByName(
              this.polarisCallContext, catalogPath, expectedType, expectedSubType, expectedName);

      // should be success, nothing changed
      Assertions.assertThat(result).isNotNull();

      // should be success
      Assertions.assertThat(result.isSuccess()).isTrue();

      // same id
      Assertions.assertThat(result.getEntity().getId()).isEqualTo(entity.getId());
    } else {
      // make sure any other timestamps are 0
      Assertions.assertThat(entity.getDropTimestamp()).isNotZero();

      // we should not find it
      EntityResult result =
          polarisMetaStoreManager.readEntityByName(
              this.polarisCallContext, catalogPath, expectedType, expectedSubType, expectedName);

      // lookup must be success, nothing changed
      Assertions.assertThat(result).isNotNull();

      // should be success
      Assertions.assertThat(result.isSuccess()).isTrue();

      // should be null, not found
      Assertions.assertThat(result.getEntity()).isNull();
    }

    return entity;
  }

  /**
   * Validate that the specified identity identified by the pair catalogId, entityId does not exist.
   *
   * @param catalogId catalog id of that entity
   * @param entityId the entity id
   * @param expectedType its expected type
   */
  private void ensureNotExistsById(long catalogId, long entityId, PolarisEntityType expectedType) {

    PolarisBaseEntity entity =
        polarisMetaStoreManager
            .loadEntity(this.polarisCallContext, catalogId, entityId, expectedType)
            .getEntity();

    // assert entity was not found
    Assertions.assertThat(entity).isNull();
  }

  /**
   * Check if the specified grant record exists
   *
   * @param grantRecords list of grant records
   * @param securable the securable
   * @param grantee the grantee
   * @param priv privilege that was granted
   */
  boolean isGrantRecordExists(
      List<PolarisGrantRecord> grantRecords,
      PolarisEntityCore securable,
      PolarisEntityCore grantee,
      PolarisPrivilege priv) {
    // ensure that this grant record is present
    long grantCount =
        grantRecords.stream()
            .filter(
                gr ->
                    gr.getSecurableCatalogId() == securable.getCatalogId()
                        && gr.getSecurableId() == securable.getId()
                        && gr.getGranteeCatalogId() == grantee.getCatalogId()
                        && gr.getGranteeId() == grantee.getId()
                        && gr.getPrivilegeCode() == priv.getCode())
            .count();
    return grantCount == 1;
  }

  /**
   * Ensure that the specified grant record exists
   *
   * @param grantRecords list of grant records
   * @param securable the securable
   * @param grantee the grantee
   * @param priv privilege that was granted
   */
  void checkGrantRecordExists(
      List<PolarisGrantRecord> grantRecords,
      PolarisEntityCore securable,
      PolarisEntityCore grantee,
      PolarisPrivilege priv) {
    // ensure that this grant record is present
    boolean exists = this.isGrantRecordExists(grantRecords, securable, grantee, priv);
    Assertions.assertThat(exists).isTrue();
  }

  /**
   * Ensure that the specified grant record has been removed
   *
   * @param grantRecords list of grant records
   * @param securable the securable
   * @param grantee the grantee
   * @param priv privilege that was granted
   */
  void checkGrantRecordRemoved(
      List<PolarisGrantRecord> grantRecords,
      PolarisEntityCore securable,
      PolarisEntityCore grantee,
      PolarisPrivilege priv) {
    // ensure that this grant record is absent
    boolean exists = this.isGrantRecordExists(grantRecords, securable, grantee, priv);
    Assertions.assertThat(exists).isFalse();
  }

  /**
   * Ensure that the specified grant record has been properly persisted
   *
   * @param securable the securable
   * @param grantee the grantee
   * @param priv privilege that was granted
   */
  void ensureGrantRecordExists(
      PolarisEntityCore securable, PolarisEntityCore grantee, PolarisPrivilege priv) {
    // re-load both entities, ensure not null
    securable =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext,
                securable.getCatalogId(),
                securable.getId(),
                securable.getType())
            .getEntity();
    Assertions.assertThat(securable).isNotNull();
    grantee =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext, grantee.getCatalogId(), grantee.getId(), grantee.getType())
            .getEntity();
    Assertions.assertThat(grantee).isNotNull();

    // the grantee better be a grantee
    Assertions.assertThat(grantee.getType().isGrantee()).isTrue();

    // load all grant records on that securable, should not fail
    LoadGrantsResult loadGrantsOnSecurable =
        polarisMetaStoreManager.loadGrantsOnSecurable(this.polarisCallContext, securable);
    // ensure entities for these grant records have been properly loaded
    this.validateLoadedGrants(loadGrantsOnSecurable, false);

    // check that the grant record exists in the list
    this.checkGrantRecordExists(loadGrantsOnSecurable.getGrantRecords(), securable, grantee, priv);

    // load all grant records on that grantee, should not fail
    LoadGrantsResult loadGrantsOnGrantee =
        polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, grantee);
    // ensure entities for these grant records have been properly loaded
    this.validateLoadedGrants(loadGrantsOnGrantee, true);

    // check that the grant record exists
    this.checkGrantRecordExists(loadGrantsOnGrantee.getGrantRecords(), securable, grantee, priv);
  }

  /**
   * Validate the return of loadGrantsToGrantee() or loadGrantsOnSecurable()
   *
   * @param loadGrantRecords return from calling loadGrantsToGrantee()/loadGrantsOnSecurable()
   * @param isGrantee if true, loadGrantsToGrantee() was called, else loadGrantsOnSecurable() was
   *     called
   */
  private void validateLoadedGrants(LoadGrantsResult loadGrantRecords, boolean isGrantee) {
    // ensure not null
    Assertions.assertThat(loadGrantRecords).isNotNull();

    // ensure that entities have been populated
    Map<Long, PolarisBaseEntity> entities = loadGrantRecords.getEntitiesAsMap();
    Assertions.assertThat(entities).isNotNull();

    // ensure all present
    for (PolarisGrantRecord grantRecord : loadGrantRecords.getGrantRecords()) {

      long catalogId =
          isGrantee ? grantRecord.getSecurableCatalogId() : grantRecord.getGranteeCatalogId();
      long entityId = isGrantee ? grantRecord.getSecurableId() : grantRecord.getGranteeId();

      // load that entity
      PolarisBaseEntity entity = null;
      for (PolarisEntityType type : PolarisEntityType.values()) {
        EntityResult entityResult =
            polarisMetaStoreManager.loadEntity(this.polarisCallContext, catalogId, entityId, type);
        if (entityResult.isSuccess()) {
          entity = entityResult.getEntity();
          break;
        }
      }
      Assertions.assertThat(entity).isNotNull();
      Assertions.assertThat(entities.get(entityId)).isEqualTo(entity);
    }
  }

  /**
   * Ensure that the specified grant record has been properly removed
   *
   * @param securable the securable
   * @param grantee the grantee
   * @param priv privilege that was granted
   */
  void ensureGrantRecordRemoved(
      PolarisEntityCore securable, PolarisEntityCore grantee, PolarisPrivilege priv) {
    // re-load both entities, ensure not null
    securable =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext,
                securable.getCatalogId(),
                securable.getId(),
                securable.getType())
            .getEntity();
    Assertions.assertThat(securable).isNotNull();
    grantee =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext, grantee.getCatalogId(), grantee.getId(), grantee.getType())
            .getEntity();
    Assertions.assertThat(grantee).isNotNull();

    // the grantee better be a grantee
    Assertions.assertThat(grantee.getType().isGrantee()).isTrue();

    // load all grant records on that securable, should not fail
    LoadGrantsResult loadGrantsOnSecurable =
        polarisMetaStoreManager.loadGrantsOnSecurable(this.polarisCallContext, securable);
    // ensure entities for these grant records have been properly loaded
    this.validateLoadedGrants(loadGrantsOnSecurable, false);

    // check that the grant record no longer exists
    this.checkGrantRecordRemoved(loadGrantsOnSecurable.getGrantRecords(), securable, grantee, priv);

    // load all grant records on that grantee, should not fail
    LoadGrantsResult loadGrantsOnGrantee =
        polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, grantee);
    this.validateLoadedGrants(loadGrantsOnGrantee, true);

    // check that the grant record has been removed
    this.checkGrantRecordRemoved(loadGrantsOnGrantee.getGrantRecords(), securable, grantee, priv);
  }

  /** Create a principal */
  PolarisBaseEntity createPrincipal(String name) {
    // create new principal identity
    PolarisBaseEntity principalEntity =
        new PolarisBaseEntity.Builder()
            .catalogId(PolarisEntityConstants.getNullId())
            .id(polarisMetaStoreManager.generateNewEntityId(this.polarisCallContext).getId())
            .typeCode(PolarisEntityType.PRINCIPAL.getCode())
            .subTypeCode(PolarisEntitySubType.NULL_SUBTYPE.getCode())
            .parentId(PolarisEntityConstants.getRootEntityId())
            .name(name)
            .internalProperties(
                PolarisObjectMapperUtil.serializeProperties(
                    Map.of(
                        PolarisEntityConstants.PRINCIPAL_CREDENTIAL_ROTATION_REQUIRED_STATE,
                        "true")))
            .build();

    CreatePrincipalResult createPrincipalResult =
        polarisMetaStoreManager.createPrincipal(this.polarisCallContext, principalEntity);
    Assertions.assertThat(createPrincipalResult).isNotNull();

    // ensure well created
    this.ensureExistsById(
        null,
        createPrincipalResult.getPrincipal().getId(),
        true,
        name,
        PolarisEntityType.PRINCIPAL,
        PolarisEntitySubType.NULL_SUBTYPE);

    // the client id
    PolarisPrincipalSecrets secrets = createPrincipalResult.getPrincipalSecrets();
    String clientId = secrets.getPrincipalClientId();

    // ensure secrets are properly populated
    Assertions.assertThat(secrets.getMainSecret()).isNotNull();
    Assertions.assertThat(secrets.getMainSecret().length()).isGreaterThanOrEqualTo(32);
    Assertions.assertThat(secrets.getSecondarySecret()).isNotNull();
    Assertions.assertThat(secrets.getSecondarySecret().length()).isGreaterThanOrEqualTo(32);

    // should be same principal id
    Assertions.assertThat(secrets.getPrincipalId()).isEqualTo(principalEntity.getId());

    // ensure that the secrets have been properly saved and match
    PolarisPrincipalSecrets reloadSecrets =
        polarisMetaStoreManager
            .loadPrincipalSecrets(this.polarisCallContext, clientId)
            .getPrincipalSecrets();
    Assertions.assertThat(reloadSecrets).isNotNull();
    Assertions.assertThat(reloadSecrets.getPrincipalId()).isEqualTo(secrets.getPrincipalId());
    Assertions.assertThat(reloadSecrets.getPrincipalClientId())
        .isEqualTo(secrets.getPrincipalClientId());
    Assertions.assertThat(reloadSecrets.getMainSecretHash()).isEqualTo(secrets.getMainSecretHash());
    Assertions.assertThat(reloadSecrets.getSecondarySecretHash())
        .isEqualTo(secrets.getSecondarySecretHash());

    Map<String, String> internalProperties =
        PolarisObjectMapperUtil.deserializeProperties(
            createPrincipalResult.getPrincipal().getInternalProperties());
    Assertions.assertThat(
            internalProperties.get(
                PolarisEntityConstants.PRINCIPAL_CREDENTIAL_ROTATION_REQUIRED_STATE))
        .isNotNull();

    // simulate retry if we are asked to
    if (this.doRetry) {
      // simulate that we retried
      CreatePrincipalResult newCreatePrincipalResult =
          polarisMetaStoreManager.createPrincipal(this.polarisCallContext, principalEntity);
      Assertions.assertThat(newCreatePrincipalResult).isNotNull();

      // ensure same
      Assertions.assertThat(newCreatePrincipalResult.getPrincipal().getId())
          .isEqualTo(createPrincipalResult.getPrincipal().getId());
      PolarisPrincipalSecrets newSecrets = newCreatePrincipalResult.getPrincipalSecrets();
      Assertions.assertThat(newSecrets.getPrincipalId()).isEqualTo(secrets.getPrincipalId());
      Assertions.assertThat(newSecrets.getPrincipalClientId())
          .isEqualTo(secrets.getPrincipalClientId());
      Assertions.assertThat(newSecrets.getMainSecretHash()).isEqualTo(secrets.getMainSecretHash());
      Assertions.assertThat(newSecrets.getMainSecretHash()).isEqualTo(secrets.getMainSecretHash());
    }

    secrets =
        polarisMetaStoreManager
            .rotatePrincipalSecrets(
                this.polarisCallContext,
                clientId,
                principalEntity.getId(),
                false,
                secrets.getMainSecretHash())
            .getPrincipalSecrets();
    Assertions.assertThat(secrets.getMainSecret()).isNotEqualTo(reloadSecrets.getMainSecret());

    PolarisBaseEntity reloadPrincipal =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext,
                0L,
                createPrincipalResult.getPrincipal().getId(),
                createPrincipalResult.getPrincipal().getType())
            .getEntity();
    internalProperties =
        PolarisObjectMapperUtil.deserializeProperties(reloadPrincipal.getInternalProperties());
    Assertions.assertThat(
            internalProperties.get(
                PolarisEntityConstants.PRINCIPAL_CREDENTIAL_ROTATION_REQUIRED_STATE))
        .isNull();

    // rotate the secrets, twice!
    polarisMetaStoreManager.rotatePrincipalSecrets(
        this.polarisCallContext,
        clientId,
        principalEntity.getId(),
        false,
        secrets.getMainSecretHash());
    polarisMetaStoreManager.rotatePrincipalSecrets(
        this.polarisCallContext,
        clientId,
        principalEntity.getId(),
        false,
        secrets.getMainSecretHash());

    // reload and check that now the main should be secondary
    reloadSecrets =
        polarisMetaStoreManager
            .loadPrincipalSecrets(this.polarisCallContext, clientId)
            .getPrincipalSecrets();
    Assertions.assertThat(reloadSecrets).isNotNull();
    Assertions.assertThat(reloadSecrets.getPrincipalId()).isEqualTo(secrets.getPrincipalId());
    Assertions.assertThat(reloadSecrets.getPrincipalClientId())
        .isEqualTo(secrets.getPrincipalClientId());
    Assertions.assertThat(reloadSecrets.getSecondarySecretHash())
        .isEqualTo(secrets.getMainSecretHash());
    String newMainSecretHash = reloadSecrets.getMainSecretHash();

    // reset - the previous main secret is no longer one of the secrets
    polarisMetaStoreManager.rotatePrincipalSecrets(
        this.polarisCallContext,
        clientId,
        principalEntity.getId(),
        true,
        reloadSecrets.getMainSecretHash());
    reloadSecrets =
        polarisMetaStoreManager
            .loadPrincipalSecrets(this.polarisCallContext, clientId)
            .getPrincipalSecrets();
    Assertions.assertThat(reloadSecrets).isNotNull();
    Assertions.assertThat(reloadSecrets.getPrincipalId()).isEqualTo(secrets.getPrincipalId());
    Assertions.assertThat(reloadSecrets.getPrincipalClientId())
        .isEqualTo(secrets.getPrincipalClientId());
    Assertions.assertThat(reloadSecrets.getMainSecretHash()).isNotEqualTo(newMainSecretHash);
    Assertions.assertThat(reloadSecrets.getSecondarySecretHash()).isNotEqualTo(newMainSecretHash);

    PolarisBaseEntity newPrincipal =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext, 0L, principalEntity.getId(), principalEntity.getType())
            .getEntity();
    internalProperties =
        PolarisObjectMapperUtil.deserializeProperties(newPrincipal.getInternalProperties());
    Assertions.assertThat(
            internalProperties.get(
                PolarisEntityConstants.PRINCIPAL_CREDENTIAL_ROTATION_REQUIRED_STATE))
        .isNotNull();

    // reset again. we should get new secrets and the CREDENTIAL_ROTATION_REQUIRED flag should be
    // gone
    polarisMetaStoreManager.rotatePrincipalSecrets(
        this.polarisCallContext,
        clientId,
        principalEntity.getId(),
        true,
        reloadSecrets.getMainSecretHash());
    PolarisPrincipalSecrets postResetCredentials =
        polarisMetaStoreManager
            .loadPrincipalSecrets(this.polarisCallContext, clientId)
            .getPrincipalSecrets();
    Assertions.assertThat(reloadSecrets).isNotNull();
    Assertions.assertThat(postResetCredentials.getPrincipalId())
        .isEqualTo(reloadSecrets.getPrincipalId());
    Assertions.assertThat(postResetCredentials.getPrincipalClientId())
        .isEqualTo(reloadSecrets.getPrincipalClientId());
    Assertions.assertThat(postResetCredentials.getMainSecretHash())
        .isNotEqualTo(reloadSecrets.getMainSecretHash());
    Assertions.assertThat(postResetCredentials.getSecondarySecretHash())
        .isNotEqualTo(reloadSecrets.getSecondarySecretHash());

    PolarisBaseEntity finalPrincipal =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext, 0L, principalEntity.getId(), principalEntity.getType())
            .getEntity();
    internalProperties =
        PolarisObjectMapperUtil.deserializeProperties(finalPrincipal.getInternalProperties());
    Assertions.assertThat(
            internalProperties.get(
                PolarisEntityConstants.PRINCIPAL_CREDENTIAL_ROTATION_REQUIRED_STATE))
        .isNull();

    // return it
    return finalPrincipal;
  }

  /** Create an entity */
  public PolarisBaseEntity createEntity(
      List<PolarisEntityCore> catalogPath,
      PolarisEntityType entityType,
      PolarisEntitySubType entitySubType,
      String name) {
    return createEntity(catalogPath, entityType, entitySubType, name, null);
  }

  public PolarisBaseEntity createEntity(
      List<PolarisEntityCore> catalogPath,
      PolarisEntityType entityType,
      PolarisEntitySubType entitySubType,
      String name,
      Map<String, String> properties) {
    return createEntity(
        catalogPath,
        entityType,
        entitySubType,
        name,
        polarisMetaStoreManager.generateNewEntityId(this.polarisCallContext).getId(),
        properties);
  }

  PolarisBaseEntity createEntity(
      List<PolarisEntityCore> catalogPath,
      PolarisEntityType entityType,
      PolarisEntitySubType entitySubType,
      String name,
      long entityId) {
    return createEntity(catalogPath, entityType, entitySubType, name, entityId, null);
  }

  PolarisBaseEntity createEntity(
      List<PolarisEntityCore> catalogPath,
      PolarisEntityType entityType,
      PolarisEntitySubType entitySubType,
      String name,
      long entityId,
      Map<String, String> properties) {
    long parentId;
    long catalogId;
    if (catalogPath != null) {
      catalogId = catalogPath.get(0).getId();
      parentId = catalogPath.get(catalogPath.size() - 1).getId();
    } else {
      catalogId = PolarisEntityConstants.getNullId();
      parentId = PolarisEntityConstants.getRootEntityId();
    }
    PolarisBaseEntity newEntity =
        new PolarisBaseEntity.Builder()
            .catalogId(catalogId)
            .id(entityId)
            .typeCode(entityType.getCode())
            .subTypeCode(entitySubType.getCode())
            .parentId(parentId)
            .name(name)
            .properties(convertPropertiesToJson(properties))
            .build();
    PolarisBaseEntity entity =
        polarisMetaStoreManager
            .createEntityIfNotExists(this.polarisCallContext, catalogPath, newEntity)
            .getEntity();
    Assertions.assertThat(entity).isNotNull();

    // same id
    Assertions.assertThat(entity.getId()).isEqualTo(newEntity.getId());

    // ensure well created
    this.ensureExistsById(catalogPath, entity.getId(), true, name, entityType, entitySubType);

    // retry if we are asked to
    if (this.doRetry) {
      PolarisBaseEntity retryEntity =
          polarisMetaStoreManager
              .createEntityIfNotExists(this.polarisCallContext, catalogPath, newEntity)
              .getEntity();
      Assertions.assertThat(retryEntity).isNotNull();

      // same id
      Assertions.assertThat(entity.getId()).isEqualTo(retryEntity.getId());

      // ensure well created
      this.ensureExistsById(
          catalogPath, retryEntity.getId(), true, name, entityType, entitySubType);
    }

    // return it
    return entity;
  }

  /**
   * Create an entity with a null subtype
   *
   * @return the entity
   */
  PolarisBaseEntity createEntity(
      List<PolarisEntityCore> catalogPath, PolarisEntityType entityType, String name) {
    return createEntity(catalogPath, entityType, PolarisEntitySubType.NULL_SUBTYPE, name);
  }

  PolarisBaseEntity createEntity(
      List<PolarisEntityCore> catalogPath,
      PolarisEntityType entityType,
      String name,
      Map<String, String> properties) {
    return createEntity(
        catalogPath, entityType, PolarisEntitySubType.NULL_SUBTYPE, name, properties);
  }

  /** Create a policy entity */
  PolicyEntity createPolicy(
      List<PolarisEntityCore> catalogPath, String name, PolicyType policyType) {
    return PolicyEntity.of(
        createEntity(
            catalogPath,
            PolarisEntityType.POLICY,
            name,
            Map.of("policy-type-code", Integer.toString(policyType.getCode()))));
  }

  /** Drop the entity if it exists. */
  void dropEntity(List<PolarisEntityCore> catalogPath, PolarisBaseEntity entityToDrop) {
    // see if the entity exists
    final boolean exists;
    boolean hasChildren = false;

    // check if it exists
    PolarisBaseEntity entity =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext,
                entityToDrop.getCatalogId(),
                entityToDrop.getId(),
                entityToDrop.getType())
            .getEntity();
    if (entity != null) {
      EntityResult entityFound =
          polarisMetaStoreManager.readEntityByName(
              this.polarisCallContext,
              catalogPath,
              entity.getType(),
              entity.getSubType(),
              entity.getName());
      exists = entityFound.isSuccess();

      // if exists, see if empty
      if (exists
          && (entity.getType() == PolarisEntityType.CATALOG
              || entity.getType() == PolarisEntityType.NAMESPACE)) {
        // build path
        List<PolarisEntityCore> path = new ArrayList<>();
        if (catalogPath != null) {
          path.addAll(catalogPath);
        }
        path.add(entityToDrop);

        // get all children, cannot be null
        List<EntityNameLookupRecord> children =
            polarisMetaStoreManager
                .listEntities(
                    this.polarisCallContext,
                    path,
                    PolarisEntityType.NAMESPACE,
                    PolarisEntitySubType.NULL_SUBTYPE,
                    PageToken.readEverything())
                .getEntities();
        Assertions.assertThat(children).isNotNull();
        if (children.isEmpty() && entity.getType() == PolarisEntityType.NAMESPACE) {
          children =
              polarisMetaStoreManager
                  .listEntities(
                      this.polarisCallContext,
                      path,
                      PolarisEntityType.TABLE_LIKE,
                      PolarisEntitySubType.ANY_SUBTYPE,
                      PageToken.readEverything())
                  .getEntities();
          Assertions.assertThat(children).isNotNull();
        } else if (children.isEmpty()) {
          children =
              polarisMetaStoreManager
                  .listEntities(
                      this.polarisCallContext,
                      path,
                      PolarisEntityType.CATALOG_ROLE,
                      PolarisEntitySubType.ANY_SUBTYPE,
                      PageToken.readEverything())
                  .getEntities();
          Assertions.assertThat(children).isNotNull();
          // if only one left, it can be dropped.
          if (children.size() == 1) {
            children.clear();
          }
        }
        hasChildren = !children.isEmpty();
      }
    } else {
      exists = false;
    }

    // load all the grants to ensure they are properly cleaned
    final List<PolarisBaseEntity> granteeEntities;
    final List<PolarisBaseEntity> securableEntities;
    if (exists) {
      granteeEntities =
          new ArrayList<>(
              polarisMetaStoreManager
                  .loadGrantsOnSecurable(this.polarisCallContext, entity)
                  .getEntities());
      securableEntities =
          new ArrayList<>(
              polarisMetaStoreManager
                  .loadGrantsToGrantee(this.polarisCallContext, entity)
                  .getEntities());
    } else {
      granteeEntities = List.of();
      securableEntities = List.of();
    }

    // now drop it
    Map<String, String> cleanupProperties =
        Map.of("taskId", String.valueOf(entity.getId()), "cleanupProperty", "cleanupValue");
    DropEntityResult dropResult =
        polarisMetaStoreManager.dropEntityIfExists(
            this.polarisCallContext, catalogPath, entityToDrop, cleanupProperties, true);

    // should have been dropped if exists
    if (entityToDrop.cannotBeDroppedOrRenamed()) {
      Assertions.assertThat(dropResult.isSuccess()).isFalse();
      Assertions.assertThat(dropResult.failedBecauseNotEmpty()).isFalse();
      Assertions.assertThat(dropResult.isEntityUnDroppable()).isTrue();
    } else if (exists && hasChildren) {
      Assertions.assertThat(dropResult.isSuccess()).isFalse();
      Assertions.assertThat(dropResult.failedBecauseNotEmpty()).isTrue();
      Assertions.assertThat(dropResult.isEntityUnDroppable()).isFalse();
    } else if (entityToDrop.getType() == PolarisEntityType.POLICY) {
      // When dropping policy with cleanup = true, we do not need cleanup task
      Assertions.assertThat(dropResult.isSuccess()).isEqualTo(exists);
      Assertions.assertThat(dropResult.failedBecauseNotEmpty()).isFalse();
      Assertions.assertThat(dropResult.isEntityUnDroppable()).isFalse();
      Assertions.assertThat(dropResult.getCleanupTaskId()).isNull();
    } else {
      Assertions.assertThat(dropResult.isSuccess()).isEqualTo(exists);
      Assertions.assertThat(dropResult.failedBecauseNotEmpty()).isFalse();
      Assertions.assertThat(dropResult.isEntityUnDroppable()).isFalse();
      Assertions.assertThat(dropResult.getCleanupTaskId()).isNotNull();
      PolarisBaseEntity cleanupTask =
          polarisMetaStoreManager
              .loadEntity(
                  this.polarisCallContext,
                  0L,
                  dropResult.getCleanupTaskId(),
                  PolarisEntityType.TASK)
              .getEntity();
      Assertions.assertThat(cleanupTask).isNotNull();
      Assertions.assertThat(cleanupTask.getType()).isEqualTo(PolarisEntityType.TASK);
      Assertions.assertThat(cleanupTask.getInternalProperties()).isNotNull();
      Map<String, String> internalProperties =
          PolarisObjectMapperUtil.deserializeProperties(cleanupTask.getInternalProperties());
      Assertions.assertThat(internalProperties).isEqualTo(cleanupProperties);
      Map<String, String> properties =
          PolarisObjectMapperUtil.deserializeProperties(cleanupTask.getProperties());
      Assertions.assertThat(properties).isNotNull();
      Assertions.assertThat(properties.get(PolarisTaskConstants.TASK_DATA)).isNotNull();
      PolarisBaseEntity droppedEntity =
          PolarisObjectMapperUtil.deserialize(
              properties.get(PolarisTaskConstants.TASK_DATA), PolarisBaseEntity.class);
      Assertions.assertThat(droppedEntity).isNotNull();
      Assertions.assertThat(droppedEntity.getId()).isEqualTo(entity.getId());
    }

    // verify gone if it was dropped
    if (dropResult.isSuccess()) {
      // should be found but deleted
      PolarisBaseEntity entityAfterDrop =
          polarisMetaStoreManager
              .loadEntity(
                  this.polarisCallContext,
                  entityToDrop.getCatalogId(),
                  entityToDrop.getId(),
                  entityToDrop.getType())
              .getEntity();

      // ensure dropped
      Assertions.assertThat(entityAfterDrop).isNull();

      // should no longer exists
      Assertions.assertThat(entity).isNotNull();
      EntityResult entityFound =
          polarisMetaStoreManager.readEntityByName(
              this.polarisCallContext,
              catalogPath,
              entity.getType(),
              entity.getSubType(),
              entity.getName());

      // should not be found
      Assertions.assertThat(entityFound.getReturnStatus())
          .isEqualTo(BaseResult.ReturnStatus.ENTITY_NOT_FOUND);

      // make sure that the entity which was dropped is no longer referenced by a grant with any
      // of the entity it was connected with before being dropped
      for (PolarisBaseEntity connectedEntity : granteeEntities) {
        LoadGrantsResult grantResult =
            polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, connectedEntity);
        if (grantResult.isSuccess()) {
          long cnt =
              grantResult.getGrantRecords().stream()
                  .filter(gr -> gr.getSecurableId() == entityToDrop.getId())
                  .count();
          Assertions.assertThat(cnt).isZero();
        } else {
          // special case when a catalog is dropped, the catalog_admin role is also dropped with it
          Assertions.assertThat(
                  grantResult.getReturnStatus() == BaseResult.ReturnStatus.ENTITY_NOT_FOUND
                      && entityToDrop.getType() == PolarisEntityType.CATALOG
                      && connectedEntity.getType() == PolarisEntityType.CATALOG_ROLE
                      && connectedEntity
                          .getName()
                          .equals(PolarisEntityConstants.getNameOfCatalogAdminRole()))
              .isTrue();
        }
      }
      for (PolarisBaseEntity connectedEntity : securableEntities) {
        LoadGrantsResult grantResult =
            polarisMetaStoreManager.loadGrantsOnSecurable(this.polarisCallContext, connectedEntity);
        long cnt =
            grantResult.getGrantRecords().stream()
                .filter(gr -> gr.getGranteeId() == entityToDrop.getId())
                .count();
        Assertions.assertThat(cnt).isZero();
      }
    }
  }

  /** Grant a privilege to a catalog role */
  public void grantPrivilege(
      PolarisBaseEntity role,
      List<PolarisEntityCore> catalogPath,
      PolarisBaseEntity securable,
      PolarisPrivilege priv) {
    // grant the privilege
    polarisMetaStoreManager.grantPrivilegeOnSecurableToRole(
        this.polarisCallContext, role, catalogPath, securable, priv);

    // now validate the privilege
    this.ensureGrantRecordExists(securable, role, priv);
  }

  /** Revoke a privilege from a catalog role */
  void revokePrivilege(
      PolarisBaseEntity role,
      List<PolarisEntityCore> catalogPath,
      PolarisBaseEntity securable,
      PolarisPrivilege priv) {
    // grant the privilege
    polarisMetaStoreManager.revokePrivilegeOnSecurableFromRole(
        this.polarisCallContext, role, catalogPath, securable, priv);

    // now validate the privilege
    this.ensureGrantRecordRemoved(securable, role, priv);
  }

  /** Grant a privilege to a catalog role */
  void grantToGrantee(
      PolarisEntityCore catalog,
      PolarisBaseEntity granted,
      PolarisBaseEntity grantee,
      PolarisPrivilege priv) {
    // grant the privilege
    polarisMetaStoreManager.grantUsageOnRoleToGrantee(
        this.polarisCallContext, catalog, granted, grantee);

    // now validate the privilege
    this.ensureGrantRecordExists(granted, grantee, priv);
  }

  /** Grant a privilege to a catalog role */
  void revokeToGrantee(
      PolarisEntityCore catalog,
      PolarisBaseEntity granted,
      PolarisBaseEntity grantee,
      PolarisPrivilege priv) {
    // revoked the privilege
    polarisMetaStoreManager.revokeUsageOnRoleFromGrantee(
        this.polarisCallContext, catalog, granted, grantee);

    // now validate that the privilege is gone
    this.ensureGrantRecordRemoved(granted, grantee, priv);
  }

  /** attach a policy to a target */
  void attachPolicyToTarget(
      List<PolarisEntityCore> targetCatalogPath,
      PolarisBaseEntity target,
      List<PolarisEntityCore> policyCatalogPath,
      PolicyEntity policy) {
    attachPolicyToTarget(targetCatalogPath, target, policyCatalogPath, policy, null);
  }

  void attachPolicyToTarget(
      List<PolarisEntityCore> targetCatalogPath,
      PolarisBaseEntity target,
      List<PolarisEntityCore> policyCatalogPath,
      PolicyEntity policy,
      Map<String, String> parameters) {
    polarisMetaStoreManager.attachPolicyToEntity(
        polarisCallContext, targetCatalogPath, target, policyCatalogPath, policy, parameters);

    ensurePolicyMappingRecordExists(target, policy, parameters);
  }

  /** detach a policy from a target */
  void detachPolicyFromTarget(
      List<PolarisEntityCore> targetCatalogPath,
      PolarisBaseEntity target,
      List<PolarisEntityCore> policyCatalogPath,
      PolicyEntity policy) {
    polarisMetaStoreManager.detachPolicyFromEntity(
        polarisCallContext, targetCatalogPath, target, policyCatalogPath, policy);
    ensurePolicyMappingRecordRemoved(target, policy);
  }

  /**
   * Ensure that the specified policy mapping record exists
   *
   * @param target the target
   * @param policy the policy
   * @param parameters the parameters
   */
  void ensurePolicyMappingRecordExists(
      PolarisBaseEntity target, PolicyEntity policy, Map<String, String> parameters) {
    target =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext, target.getCatalogId(), target.getId(), target.getType())
            .getEntity();
    Assertions.assertThat(target).isNotNull();

    policy =
        PolicyEntity.of(
            polarisMetaStoreManager
                .loadEntity(
                    this.polarisCallContext,
                    policy.getCatalogId(),
                    policy.getId(),
                    PolarisEntityType.POLICY)
                .getEntity());
    Assertions.assertThat(policy).isNotNull();

    LoadPolicyMappingsResult loadPolicyMappingsResult =
        polarisMetaStoreManager.loadPoliciesOnEntity(this.polarisCallContext, target);

    validateLoadedPolicyMappings(loadPolicyMappingsResult);

    checkPolicyMappingRecordExists(
        loadPolicyMappingsResult.getPolicyMappingRecords(), target, policy, parameters);

    // also try load by specific type
    LoadPolicyMappingsResult loadPolicyMappingsResultByType =
        polarisMetaStoreManager.loadPoliciesOnEntityByType(
            this.polarisCallContext, target, policy.getPolicyType());
    validateLoadedPolicyMappings(loadPolicyMappingsResultByType);
    checkPolicyMappingRecordExists(
        loadPolicyMappingsResultByType.getPolicyMappingRecords(), target, policy, parameters);
  }

  /**
   * Ensure that the specified policy mapping record has been removed
   *
   * @param target the target
   * @param policy the policy
   */
  void ensurePolicyMappingRecordRemoved(PolarisBaseEntity target, PolicyEntity policy) {
    target =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext, target.getCatalogId(), target.getId(), target.getType())
            .getEntity();
    Assertions.assertThat(target).isNotNull();

    policy =
        PolicyEntity.of(
            polarisMetaStoreManager
                .loadEntity(
                    this.polarisCallContext,
                    policy.getCatalogId(),
                    policy.getId(),
                    PolarisEntityType.POLICY)
                .getEntity());
    Assertions.assertThat(policy).isNotNull();

    LoadPolicyMappingsResult loadPolicyMappingsResult =
        polarisMetaStoreManager.loadPoliciesOnEntity(this.polarisCallContext, target);

    validateLoadedPolicyMappings(loadPolicyMappingsResult);

    checkPolicyMappingRecordRemoved(
        loadPolicyMappingsResult.getPolicyMappingRecords(), target, policy);

    // also try load by specific type
    LoadPolicyMappingsResult loadPolicyMappingsResultByType =
        polarisMetaStoreManager.loadPoliciesOnEntityByType(
            this.polarisCallContext, target, policy.getPolicyType());
    validateLoadedPolicyMappings(loadPolicyMappingsResultByType);
    checkPolicyMappingRecordRemoved(
        loadPolicyMappingsResultByType.getPolicyMappingRecords(), target, policy);
  }

  /**
   * Validate the return of loadPoliciesOnEntity() or LoadPoliciesOnEntityByType()
   *
   * @param loadPolicyMappingRecords return from calling
   *     loadPoliciesOnEntity()/LoadPoliciesOnEntityByType()
   */
  void validateLoadedPolicyMappings(LoadPolicyMappingsResult loadPolicyMappingRecords) {
    Assertions.assertThat(loadPolicyMappingRecords).isNotNull();

    Map<Long, PolarisBaseEntity> policyEntities = loadPolicyMappingRecords.getEntitiesAsMap();
    Assertions.assertThat(policyEntities).isNotNull();

    for (PolarisPolicyMappingRecord policyMappingRecord :
        loadPolicyMappingRecords.getPolicyMappingRecords()) {
      PolicyEntity entity =
          PolicyEntity.of(
              polarisMetaStoreManager
                  .loadEntity(
                      this.polarisCallContext,
                      policyMappingRecord.getPolicyCatalogId(),
                      policyMappingRecord.getPolicyId(),
                      PolarisEntityType.POLICY)
                  .getEntity());

      Assertions.assertThat(entity).isNotNull();
      Assertions.assertThat(policyEntities.get(entity.getId())).isEqualTo(entity);
    }
  }

  /**
   * Check that the policy mapping record exists
   *
   * @param policyMappingRecords list of policy mapping records
   * @param target the target
   * @param policy the policy
   */
  void checkPolicyMappingRecordExists(
      List<PolarisPolicyMappingRecord> policyMappingRecords,
      PolarisBaseEntity target,
      PolicyEntity policy,
      Map<String, String> parameters) {
    boolean exists = isPolicyMappingRecordExists(policyMappingRecords, target, policy, parameters);
    Assertions.assertThat(exists).isTrue();
  }

  /**
   * Check that the policy mapping record has been removed
   *
   * @param policyMappingRecords list of policy mapping records
   * @param target the target
   * @param policy the policy
   */
  void checkPolicyMappingRecordRemoved(
      List<PolarisPolicyMappingRecord> policyMappingRecords,
      PolarisBaseEntity target,
      PolicyEntity policy) {
    boolean exists = isPolicyMappingRecordExists(policyMappingRecords, target, policy);
    Assertions.assertThat(exists).isFalse();
  }

  /**
   * Check if the policy mapping record exists
   *
   * @param policyMappingRecords list of policy mapping records
   * @param target the target
   * @param policy the policy
   */
  boolean isPolicyMappingRecordExists(
      List<PolarisPolicyMappingRecord> policyMappingRecords,
      PolarisBaseEntity target,
      PolicyEntity policy) {
    long policyMappingCount =
        policyMappingRecords.stream()
            .filter(
                record ->
                    record.getPolicyCatalogId() == policy.getCatalogId()
                        && record.getPolicyId() == policy.getId()
                        && record.getTargetCatalogId() == target.getCatalogId()
                        && record.getTargetId() == target.getId()
                        && record.getPolicyTypeCode() == policy.getPolicyTypeCode())
            .count();
    return policyMappingCount == 1;
  }

  /**
   * Check if the policy mapping record exists and verify if the parameters also equals
   *
   * @param policyMappingRecords list of policy mapping records
   * @param target the target
   * @param policy the policy
   * @param parameters the parameters
   */
  boolean isPolicyMappingRecordExists(
      List<PolarisPolicyMappingRecord> policyMappingRecords,
      PolarisBaseEntity target,
      PolicyEntity policy,
      Map<String, String> parameters) {
    PolarisPolicyMappingRecord expected =
        new PolarisPolicyMappingRecord(
            target.getCatalogId(),
            target.getId(),
            policy.getCatalogId(),
            policy.getId(),
            policy.getPolicyTypeCode(),
            parameters);
    long policyMappingCount =
        policyMappingRecords.stream().filter(record -> expected.equals(record)).count();
    return policyMappingCount == 1;
  }

  /**
   * Create a test catalog. This is a new catalog which will have the following objects (N is for a
   * namespace, T for a table, V for a view, R for a role, P for a principal, POL for a policy):
   *
   * <pre>
   * - C
   * - (N1/N2/T1)
   * - (N1/N2/T2)
   * - (N1/N2/V1)
   * - (N1/N3/T3)
   * - (N1/N3/V2)
   * - (N1/T4)
   * - (N1/N4)
   * - N5/N6/T5
   * - N5/N6/T6
   * - N7/N8/POL1
   * - N7/N8/POL2
   * - N7/POL3
   * - R1(TABLE_READ on N1/N2, VIEW_CREATE on C, TABLE_LIST on N1/N2, TABLE_DROP on N5/N6/T5)
   * - R2(TABLE_WRITE_DATA on N5, VIEW_LIST on C)
   * - PR1(R1, R2)
   * - PR2(R2)
   * - P1(PR1, PR2)
   * - P2(PR1)
   * </pre>
   */
  PolarisBaseEntity createTestCatalog(String catalogName) {
    // create new catalog
    PolarisBaseEntity catalog =
        new PolarisBaseEntity(
            PolarisEntityConstants.getNullId(),
            polarisMetaStoreManager.generateNewEntityId(this.polarisCallContext).getId(),
            PolarisEntityType.CATALOG,
            PolarisEntitySubType.NULL_SUBTYPE,
            PolarisEntityConstants.getRootEntityId(),
            catalogName);
    CreateCatalogResult catalogCreated =
        polarisMetaStoreManager.createCatalog(this.polarisCallContext, catalog, List.of());
    Assertions.assertThat(catalogCreated).isNotNull();
    catalog = catalogCreated.getCatalog();

    // now create all objects
    PolarisBaseEntity N1 = this.createEntity(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.createEntity(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    this.createEntity(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T1");
    this.createEntity(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T2");
    this.createEntity(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_VIEW,
        "V1");
    PolarisBaseEntity N1_N3 =
        this.createEntity(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N3");
    this.createEntity(
        List.of(catalog, N1, N1_N3),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T3");
    this.createEntity(
        List.of(catalog, N1, N1_N3),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_VIEW,
        "V2");
    this.createEntity(
        List.of(catalog, N1),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T4");
    this.createEntity(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N4");
    PolarisBaseEntity N5 = this.createEntity(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");
    PolarisBaseEntity N5_N6 =
        this.createEntity(List.of(catalog, N5), PolarisEntityType.NAMESPACE, "N6");
    PolarisBaseEntity N5_N6_T5 =
        this.createEntity(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T5");
    this.createEntity(
        List.of(catalog, N5, N5_N6),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T6");

    PolarisBaseEntity N7 = this.createEntity(List.of(catalog), PolarisEntityType.NAMESPACE, "N7");
    PolarisBaseEntity N7_N8 =
        this.createEntity(List.of(catalog, N7), PolarisEntityType.NAMESPACE, "N8");
    this.createPolicy(List.of(catalog, N7, N7_N8), "POL1", PredefinedPolicyTypes.DATA_COMPACTION);
    this.createPolicy(
        List.of(catalog, N7, N7_N8), "POL2", PredefinedPolicyTypes.METADATA_COMPACTION);
    this.createPolicy(List.of(catalog, N7), "POL3", PredefinedPolicyTypes.SNAPSHOT_EXPIRY);

    // the two catalog roles
    PolarisBaseEntity R1 =
        this.createEntity(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R1");
    PolarisBaseEntity R2 =
        this.createEntity(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R2");

    // perform the grants to R1
    grantPrivilege(R1, List.of(catalog, N1, N1_N2), N1_N2, PolarisPrivilege.TABLE_READ_DATA);
    grantPrivilege(R1, List.of(catalog), catalog, PolarisPrivilege.VIEW_CREATE);
    grantPrivilege(R1, List.of(catalog, N5), N5, PolarisPrivilege.TABLE_LIST);
    grantPrivilege(R1, List.of(catalog, N1, N5_N6), N5_N6_T5, PolarisPrivilege.TABLE_DROP);

    // perform the grants to R2
    grantPrivilege(R2, List.of(catalog, N5), N5, PolarisPrivilege.TABLE_WRITE_DATA);
    grantPrivilege(R2, List.of(catalog), catalog, PolarisPrivilege.VIEW_LIST);

    // now create two principal roles
    PolarisBaseEntity PR1 = this.createEntity(null, PolarisEntityType.PRINCIPAL_ROLE, "PR1");
    PolarisBaseEntity PR2 = this.createEntity(null, PolarisEntityType.PRINCIPAL_ROLE, "PR2");

    // assign R1 and R2 to PR1
    grantToGrantee(catalog, R1, PR1, PolarisPrivilege.CATALOG_ROLE_USAGE);
    grantToGrantee(catalog, R2, PR1, PolarisPrivilege.CATALOG_ROLE_USAGE);
    grantToGrantee(catalog, R2, PR2, PolarisPrivilege.CATALOG_ROLE_USAGE);

    // also create two new principals
    PolarisBaseEntity P1 = this.createPrincipal("P1");
    PolarisBaseEntity P2 = this.createPrincipal("P2");

    // assign PR1 and PR2 to this principal
    grantToGrantee(null, PR1, P1, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);
    grantToGrantee(null, PR2, P1, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);
    grantToGrantee(null, PR2, P2, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);

    return catalog;
  }

  /**
   * Find and entity by name, ensure it is there and has been properly initialized
   *
   * @return the identity we found
   */
  public PolarisBaseEntity ensureExistsByName(
      List<PolarisEntityCore> catalogPath,
      PolarisEntityType entityType,
      PolarisEntitySubType entitySubType,
      String name) {
    // find by name, ensure we found it
    EntityResult entityFound =
        polarisMetaStoreManager.readEntityByName(
            this.polarisCallContext, catalogPath, entityType, entitySubType, name);
    Assertions.assertThat(entityFound).isNotNull();
    Assertions.assertThat(entityFound.isSuccess()).isTrue();

    PolarisBaseEntity entity = entityFound.getEntity();
    Assertions.assertThat(entity).isNotNull();
    Assertions.assertThat(entity.getName()).isEqualTo(name);
    Assertions.assertThat(entity.getType()).isEqualTo(entityType);
    if (entitySubType != PolarisEntitySubType.ANY_SUBTYPE) {
      Assertions.assertThat(entity.getSubType()).isEqualTo(entitySubType);
    }
    Assertions.assertThat(this.testStartTime).isLessThanOrEqualTo(entity.getCreateTimestamp());
    Assertions.assertThat(entity.getDropTimestamp()).isZero();
    Assertions.assertThat(entity.getCreateTimestamp())
        .isLessThanOrEqualTo(entity.getLastUpdateTimestamp());
    Assertions.assertThat(entity.getToPurgeTimestamp()).isZero();
    Assertions.assertThat(entity.getPurgeTimestamp()).isZero();
    Assertions.assertThat(entity.getCatalogId())
        .isEqualTo(
            (catalogPath == null)
                ? PolarisEntityConstants.getNullId()
                : catalogPath.get(0).getId());
    Assertions.assertThat(entity.getParentId())
        .isEqualTo(
            (catalogPath == null)
                ? PolarisEntityConstants.getRootEntityId()
                : catalogPath.get(catalogPath.size() - 1).getId());
    Assertions.assertThat(entity.getEntityVersion() >= 1 && entity.getGrantRecordsVersion() >= 1)
        .isTrue();

    return entity;
  }

  /**
   * Find and entity by name, ensure it is there and has been properly initialized
   *
   * @return the identity we found
   */
  public PolarisBaseEntity ensureExistsByName(
      List<PolarisEntityCore> catalogPath, PolarisEntityType entityType, String name) {
    return this.ensureExistsByName(
        catalogPath, entityType, PolarisEntitySubType.NULL_SUBTYPE, name);
  }

  /**
   * Update the specified entity. Validate that versions are properly maintained
   *
   * @param catalogPath path to the catalog where this entity is stored
   * @param entity entity to update
   * @param props updated properties
   * @param internalProps updated internal properties
   * @return updated entity
   */
  public PolarisBaseEntity updateEntity(
      List<PolarisEntityCore> catalogPath,
      PolarisBaseEntity entity,
      String props,
      String internalProps) {
    // ok, remember version and grants_version
    int version = entity.getEntityVersion();
    int grantRecsVersion = entity.getGrantRecordsVersion();

    // derive the catalogId for that entity
    long catalogId =
        (catalogPath == null) ? PolarisEntityConstants.getNullId() : catalogPath.get(0).getId();
    Assertions.assertThat(catalogId).isEqualTo(entity.getCatalogId());
    // let's make some property updates
    PolarisBaseEntity updatedPropEntity =
        new PolarisBaseEntity.Builder(entity)
            .properties(props)
            .internalProperties(internalProps)
            .build();

    // lookup that entity, ensure it exists
    PolarisBaseEntity beforeUpdateEntity =
        polarisMetaStoreManager
            .loadEntity(
                this.polarisCallContext,
                updatedPropEntity.getCatalogId(),
                updatedPropEntity.getId(),
                updatedPropEntity.getType())
            .getEntity();

    // update that property
    PolarisBaseEntity updatedEntity =
        polarisMetaStoreManager
            .updateEntityPropertiesIfNotChanged(
                this.polarisCallContext, catalogPath, updatedPropEntity)
            .getEntity();

    // if version mismatch, nothing should be updated
    if (beforeUpdateEntity == null
        || beforeUpdateEntity.getEntityVersion() != updatedPropEntity.getEntityVersion()) {
      Assertions.assertThat(updatedEntity).isNull();

      // refresh catalog info
      entity =
          polarisMetaStoreManager
              .loadEntity(
                  this.polarisCallContext,
                  updatedPropEntity.getCatalogId(),
                  updatedPropEntity.getId(),
                  updatedPropEntity.getType())
              .getEntity();

      // ensure nothing has changed
      if (beforeUpdateEntity != null && entity != null) {
        Assertions.assertThat(entity.getEntityVersion())
            .isEqualTo(beforeUpdateEntity.getEntityVersion());
        Assertions.assertThat(entity.getGrantRecordsVersion())
            .isEqualTo(beforeUpdateEntity.getGrantRecordsVersion());
        Assertions.assertThat(entity.getProperties()).isEqualTo(beforeUpdateEntity.getProperties());
        Assertions.assertThat(entity.getInternalProperties())
            .isEqualTo(beforeUpdateEntity.getInternalProperties());
      }

      return null;
    }

    // entity should have been updated
    Assertions.assertThat(updatedEntity).isNotNull();

    // read back this entity and ensure that the update was performed
    PolarisBaseEntity afterUpdateEntity =
        this.ensureExistsById(
            catalogPath,
            entity.getId(),
            true,
            entity.getName(),
            entity.getType(),
            entity.getSubType());

    // verify that version has changed, but not grantRecsVersion
    Assertions.assertThat(updatedEntity.getEntityVersion()).isEqualTo(version + 1);
    Assertions.assertThat(entity.getEntityVersion()).isEqualTo(version);
    Assertions.assertThat(afterUpdateEntity.getEntityVersion()).isEqualTo(version + 1);

    // grantRecsVersion should not have changed
    Assertions.assertThat(updatedEntity.getGrantRecordsVersion()).isEqualTo(grantRecsVersion);
    Assertions.assertThat(entity.getGrantRecordsVersion()).isEqualTo(grantRecsVersion);
    Assertions.assertThat(afterUpdateEntity.getGrantRecordsVersion()).isEqualTo(grantRecsVersion);

    // update should have been performed
    Assertions.assertThat(jsonNode(updatedEntity.getProperties()))
        .isEqualTo(jsonNode(updatedPropEntity.getProperties()));
    Assertions.assertThat(jsonNode(afterUpdateEntity.getProperties()))
        .isEqualTo(jsonNode(updatedPropEntity.getProperties()));
    Assertions.assertThat(jsonNode(updatedEntity.getInternalProperties()))
        .isEqualTo(jsonNode(updatedPropEntity.getInternalProperties()));
    Assertions.assertThat(jsonNode(afterUpdateEntity.getInternalProperties()))
        .isEqualTo(jsonNode(updatedPropEntity.getInternalProperties()));

    // lookup the tracking slice to verify this has been updated too
    if (supportsChangeTracking) {
      List<PolarisChangeTrackingVersions> versions =
          polarisMetaStoreManager
              .loadEntitiesChangeTracking(
                  this.polarisCallContext,
                  List.of(new PolarisEntityId(catalogId, updatedPropEntity.getId())))
              .getChangeTrackingVersions();
      Assertions.assertThat(versions).hasSize(1);
      Assertions.assertThat(versions.get(0).getEntityVersion())
          .isEqualTo(updatedEntity.getEntityVersion());
      Assertions.assertThat(versions.get(0).getGrantRecordsVersion())
          .isEqualTo(updatedEntity.getGrantRecordsVersion());
    }

    return updatedEntity;
  }

  private JsonNode jsonNode(String json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  /** Execute a list operation and validate the result */
  private void validateListReturn(
      List<PolarisEntityCore> path,
      PolarisEntityType entityType,
      PolarisEntitySubType entitySubType,
      List<ImmutablePair<String, PolarisEntitySubType>> expectedResult) {

    // list the entities under the specified path
    List<EntityNameLookupRecord> result =
        polarisMetaStoreManager
            .listEntities(
                this.polarisCallContext,
                path,
                entityType,
                entitySubType,
                PageToken.readEverything())
            .getEntities();
    Assertions.assertThat(result).isNotNull();

    // now validate the result
    Assertions.assertThat(result).hasSameSizeAs(expectedResult);

    // ensure all elements are found
    for (Pair<String, PolarisEntitySubType> expected : expectedResult) {
      boolean found = false;
      for (EntityNameLookupRecord res : result) {
        if (res.getName().equals(expected.getLeft())
            && expected.getRight().getCode() == res.getSubTypeCode()) {
          found = true;
          break;
        }
      }
      // we should find it
      Assertions.assertThat(found).isTrue();
    }
  }

  /** Execute a list operation and validate the result */
  private void validateListReturn(
      List<PolarisEntityCore> path,
      PolarisEntityType entityType,
      List<ImmutablePair<String, PolarisEntitySubType>> expectedResult) {
    validateListReturn(path, entityType, PolarisEntitySubType.NULL_SUBTYPE, expectedResult);
  }

  /**
   * Validate a cached entry which has just been loaded from the store, assuming it is not null.
   *
   * @param cacheEntry the cached entity to validate
   */
  private void validateCacheEntryLoad(ResolvedEntityResult cacheEntry) {

    // cannot be null
    Assertions.assertThat(cacheEntry).isNotNull();
    PolarisEntity entity = PolarisEntity.of(cacheEntry.getEntity());
    Assertions.assertThat(entity).isNotNull();
    List<PolarisGrantRecord> grantRecords = cacheEntry.getEntityGrantRecords();
    Assertions.assertThat(grantRecords).isNotNull();

    // same grant record version
    Assertions.assertThat(cacheEntry.getGrantRecordsVersion())
        .isEqualTo(entity.getGrantRecordsVersion());

    // reload the entity
    PolarisEntity refEntity =
        PolarisEntity.of(
            this.polarisMetaStoreManager.loadEntity(
                this.polarisCallContext, entity.getCatalogId(), entity.getId(), entity.getType()));
    Assertions.assertThat(refEntity).isNotNull();

    // same entity
    Assertions.assertThat(entity).isEqualTo(refEntity);
    // same version
    Assertions.assertThat(entity.getEntityVersion()).isEqualTo(refEntity.getEntityVersion());

    // reload the grants
    List<PolarisGrantRecord> refGrantRecords = new ArrayList<>();
    if (refEntity.getType().isGrantee()) {
      LoadGrantsResult loadGrantResult =
          this.polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, refEntity);
      this.validateLoadedGrants(loadGrantResult, true);

      // same version
      Assertions.assertThat(loadGrantResult.getGrantsVersion())
          .isEqualTo(cacheEntry.getGrantRecordsVersion());

      refGrantRecords.addAll(loadGrantResult.getGrantRecords());
    }

    LoadGrantsResult loadGrantResult =
        this.polarisMetaStoreManager.loadGrantsOnSecurable(this.polarisCallContext, refEntity);
    this.validateLoadedGrants(loadGrantResult, false);

    // same version
    Assertions.assertThat(loadGrantResult.getGrantsVersion())
        .isEqualTo(cacheEntry.getGrantRecordsVersion());

    refGrantRecords.addAll(loadGrantResult.getGrantRecords());

    // same grants
    Assertions.assertThat(new HashSet<>(grantRecords)).isEqualTo(new HashSet<>(refGrantRecords));
  }

  /**
   * Validate a cached entry which has just been refreshed from the store, assuming it is not null.
   *
   * @param cacheEntry the cached entity to validate
   */
  private void validateCacheEntryRefresh(
      ResolvedEntityResult cacheEntry,
      long catalogId,
      long entityId,
      PolarisEntityType entityType,
      int entityVersion,
      int entityGrantRecordsVersion) {
    // cannot be null
    Assertions.assertThat(cacheEntry).isNotNull();
    PolarisBaseEntity entity = cacheEntry.getEntity();
    List<PolarisGrantRecord> grantRecords = cacheEntry.getEntityGrantRecords();

    // reload the entity
    PolarisBaseEntity refEntity =
        this.polarisMetaStoreManager
            .loadEntity(this.polarisCallContext, catalogId, entityId, entityType)
            .getEntity();
    Assertions.assertThat(refEntity).isNotNull();

    // reload the grants
    LoadGrantsResult loadGrantResult =
        refEntity.getType().isGrantee()
            ? this.polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, refEntity)
            : this.polarisMetaStoreManager.loadGrantsOnSecurable(
                this.polarisCallContext, refEntity);
    this.validateLoadedGrants(loadGrantResult, refEntity.getType().isGrantee());
    Assertions.assertThat(cacheEntry.getGrantRecordsVersion())
        .isEqualTo(loadGrantResult.getGrantsVersion());

    // if entity version has not changed, entity should not be loaded
    if (refEntity.getEntityVersion() == entityVersion) {
      // no need to reload in that case
      Assertions.assertThat(entity).isNull();
    } else {
      // should have been reloaded
      Assertions.assertThat(entity).isNotNull();
      // should be same as refEntity
      Assertions.assertThat(PolarisEntity.of(entity)).isEqualTo(PolarisEntity.of(refEntity));
      // same version
      Assertions.assertThat(entity.getEntityVersion()).isEqualTo(refEntity.getEntityVersion());
    }

    // if grant records version has not changed, grant records should not be loaded
    if (refEntity.getGrantRecordsVersion() == entityGrantRecordsVersion) {
      // no need to reload in that case
      Assertions.assertThat(grantRecords).isNull();
    } else {
      List<PolarisGrantRecord> refGrantRecords = loadGrantResult.getGrantRecords();
      // should have been reloaded
      Assertions.assertThat(grantRecords).isNotNull();
      // should be same as refEntity
      Assertions.assertThat(new HashSet<>(grantRecords)).isEqualTo(new HashSet<>(refGrantRecords));
      // same version
      Assertions.assertThat(cacheEntry.getGrantRecordsVersion())
          .isEqualTo(loadGrantResult.getGrantsVersion());
    }
  }

  /**
   * Helper function to validate loading the cache by name. We will load the cache entry by name,
   * check that the result is correct and return the entity or null if it cannot be found.
   *
   * @param entityCatalogId catalog id for the entity
   * @param parentId parent id of the entity
   * @param entityType type of the entity
   * @param entityName name of the entity
   * @param expectExists if true, we should find it
   * @return return just the entity
   */
  private PolarisBaseEntity loadCacheEntryByName(
      long entityCatalogId,
      long parentId,
      @Nonnull PolarisEntityType entityType,
      @Nonnull String entityName,
      boolean expectExists) {
    // load cached entry
    ResolvedEntityResult cacheEntry =
        this.polarisMetaStoreManager.loadResolvedEntityByName(
            this.polarisCallContext, entityCatalogId, parentId, entityType, entityName);

    // if null, validate that indeed the entry does not exist
    Assertions.assertThat(cacheEntry.isSuccess()).isEqualTo(expectExists);

    // if not null, validate it
    if (cacheEntry.isSuccess()) {
      this.validateCacheEntryLoad(cacheEntry);
      return cacheEntry.getEntity();
    } else {
      return null;
    }
  }

  /**
   * Helper function to validate loading the cache by name. We will load the cache entry by name,
   * check that the result exists and is correct and return the entity.
   *
   * @param entityCatalogId catalog id for the entity
   * @param parentId parent id of the entity
   * @param entityType type of the entity
   * @param entityName name of the entity
   * @return return just the entity
   */
  private PolarisBaseEntity loadCacheEntryByName(
      long entityCatalogId,
      long parentId,
      @Nonnull PolarisEntityType entityType,
      @Nonnull String entityName) {
    return this.loadCacheEntryByName(entityCatalogId, parentId, entityType, entityName, true);
  }

  /**
   * Helper function to validate loading the cache by id. We will load the cache entry by id, check
   * that the result is correct and return the entity or null if it cannot be found.
   *
   * @param entityCatalogId catalog id for the entity
   * @param entityId parent id of the entity
   * @param expectExists if true, we should find it
   * @return return just the entity
   */
  private PolarisBaseEntity loadCacheEntryById(
      long entityCatalogId, long entityId, PolarisEntityType entityType, boolean expectExists) {
    // load cached entry
    ResolvedEntityResult cacheEntry =
        this.polarisMetaStoreManager.loadResolvedEntityById(
            this.polarisCallContext, entityCatalogId, entityId, entityType);

    // if null, validate that indeed the entry does not exist
    Assertions.assertThat(cacheEntry.isSuccess()).isEqualTo(expectExists);

    // if not null, validate it
    if (cacheEntry.isSuccess()) {
      this.validateCacheEntryLoad(cacheEntry);
      return cacheEntry.getEntity();
    } else {
      return null;
    }
  }

  /**
   * Helper function to validate loading the cache by id. We will load the cache entry by id, check
   * that it exists and validate the result.
   *
   * @param entityCatalogId catalog id for the entity
   * @param entityId parent id of the entity
   * @return return just the entity
   */
  private PolarisBaseEntity loadCacheEntryById(
      long entityCatalogId, long entityId, PolarisEntityType entityType) {
    return this.loadCacheEntryById(entityCatalogId, entityId, entityType, true);
  }

  /**
   * Helper function to validate the refresh of a cached entry. We will refresh the cache entry and
   * check if the result exists based on "expectExists" and, if exists, validate it is correct
   *
   * @param entityVersion entity version in the cache
   * @param entityGrantRecordsVersion entity grant records version in the cache
   * @param entityType type of the entity to load
   * @param entityCatalogId catalog id for the entity
   * @param entityId parent id of the entity
   * @param expectExists if true, we should find it
   */
  private void refreshCacheEntry(
      int entityVersion,
      int entityGrantRecordsVersion,
      PolarisEntityType entityType,
      long entityCatalogId,
      long entityId,
      boolean expectExists) {
    // load cached entry
    ResolvedEntityResult cacheEntry =
        this.polarisMetaStoreManager.refreshResolvedEntity(
            this.polarisCallContext,
            entityVersion,
            entityGrantRecordsVersion,
            entityType,
            entityCatalogId,
            entityId);

    // if null, validate that indeed the entry does not exist
    Assertions.assertThat(cacheEntry.isSuccess()).isEqualTo(expectExists);

    // if not null, validate it
    if (cacheEntry.isSuccess()) {
      this.validateCacheEntryRefresh(
          cacheEntry,
          entityCatalogId,
          entityId,
          entityType,
          entityVersion,
          entityGrantRecordsVersion);
    }
  }

  /**
   * Helper function to validate the refresh of a cached entry. We will refresh the cache entry and
   * check that the result exists and is correct
   *
   * @param entityVersion entity version in the cache
   * @param entityGrantRecordsVersion entity grant records version in the cache
   * @param entityType type of the entity to load
   * @param entityCatalogId catalog id for the entity
   * @param entityId parent id of the entity
   */
  private void refreshCacheEntry(
      int entityVersion,
      int entityGrantRecordsVersion,
      @Nonnull PolarisEntityType entityType,
      long entityCatalogId,
      long entityId) {
    // refresh cached entry
    this.refreshCacheEntry(
        entityVersion, entityGrantRecordsVersion, entityType, entityCatalogId, entityId, true);
  }

  /** validate that the root catalog was properly constructed */
  void validateBootstrap() {
    // load all principals
    List<EntityNameLookupRecord> principals =
        polarisMetaStoreManager
            .listEntities(
                this.polarisCallContext,
                null,
                PolarisEntityType.PRINCIPAL,
                PolarisEntitySubType.NULL_SUBTYPE,
                PageToken.readEverything())
            .getEntities();

    // ensure not null, one element only
    Assertions.assertThat(principals).isNotNull().hasSize(1);

    // get catalog list information
    EntityNameLookupRecord principalListInfo = principals.get(0);

    // now make sure this principal was properly persisted
    PolarisBaseEntity principal =
        this.ensureExistsById(
            null,
            principalListInfo.getId(),
            true,
            PolarisEntityConstants.getRootPrincipalName(),
            PolarisEntityType.PRINCIPAL,
            PolarisEntitySubType.NULL_SUBTYPE);

    // load all principal roles
    List<EntityNameLookupRecord> principalRoles =
        polarisMetaStoreManager
            .listEntities(
                this.polarisCallContext,
                null,
                PolarisEntityType.PRINCIPAL_ROLE,
                PolarisEntitySubType.NULL_SUBTYPE,
                PageToken.readEverything())
            .getEntities();

    // ensure not null, one element only
    Assertions.assertThat(principalRoles).isNotNull().hasSize(1);

    // get catalog list information
    EntityNameLookupRecord roleListInfo = principalRoles.get(0);

    // now make sure this principal role was properly persisted
    PolarisBaseEntity principalRole =
        this.ensureExistsById(
            null,
            roleListInfo.getId(),
            true,
            PolarisEntityConstants.getNameOfPrincipalServiceAdminRole(),
            PolarisEntityType.PRINCIPAL_ROLE,
            PolarisEntitySubType.NULL_SUBTYPE);

    // also between the principal_role and the principal
    this.ensureGrantRecordExists(principalRole, principal, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);
  }

  public void testCreateTestCatalog() {
    // create test catalog
    this.createTestCatalog("test");

    // validate that it has been properly created
    PolarisBaseEntity catalog = this.ensureExistsByName(null, PolarisEntityType.CATALOG, "test");
    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T1");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T2");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ANY_SUBTYPE,
        "T2");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_VIEW,
        "V1");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ANY_SUBTYPE,
        "V1");
    PolarisBaseEntity N1_N3 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N3");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N3),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T3");
    this.ensureExistsByName(
        List.of(catalog, N1, N1_N3),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ANY_SUBTYPE,
        "V2");
    this.ensureExistsByName(
        List.of(catalog, N1),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T4");
    this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N4");
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");
    PolarisBaseEntity N5_N6 =
        this.ensureExistsByName(
            List.of(catalog, N5),
            PolarisEntityType.NAMESPACE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "N6");
    this.ensureExistsByName(
        List.of(catalog, N5, N5_N6),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T5");
    PolarisBaseEntity N5_N6_T5 =
        this.ensureExistsByName(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "T5");
    this.ensureExistsByName(
        List.of(catalog, N5, N5_N6),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        "T6");
    PolarisBaseEntity N7 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N7");
    PolarisBaseEntity N7_N8 =
        this.ensureExistsByName(
            List.of(catalog, N7),
            PolarisEntityType.NAMESPACE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "N8");
    this.ensureExistsByName(List.of(catalog, N7, N7_N8), PolarisEntityType.POLICY, "POL1");
    this.ensureExistsByName(List.of(catalog, N7, N7_N8), PolarisEntityType.POLICY, "POL2");
    this.ensureExistsByName(List.of(catalog, N7), PolarisEntityType.POLICY, "POL3");
    PolarisBaseEntity R1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R1");
    PolarisBaseEntity R2 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R2");
    this.ensureGrantRecordExists(N1_N2, R1, PolarisPrivilege.TABLE_READ_DATA);
    this.ensureGrantRecordExists(catalog, R1, PolarisPrivilege.VIEW_CREATE);
    this.ensureGrantRecordExists(N5, R1, PolarisPrivilege.TABLE_LIST);
    this.ensureGrantRecordExists(N5_N6_T5, R1, PolarisPrivilege.TABLE_DROP);
    this.ensureGrantRecordExists(N5, R2, PolarisPrivilege.TABLE_WRITE_DATA);
    this.ensureGrantRecordExists(catalog, R2, PolarisPrivilege.VIEW_LIST);
    PolarisBaseEntity PR1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL_ROLE, "PR1");
    PolarisBaseEntity PR2 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL_ROLE, "PR2");
    this.ensureGrantRecordExists(R1, PR1, PolarisPrivilege.CATALOG_ROLE_USAGE);
    this.ensureGrantRecordExists(R2, PR1, PolarisPrivilege.CATALOG_ROLE_USAGE);
    this.ensureGrantRecordExists(R2, PR2, PolarisPrivilege.CATALOG_ROLE_USAGE);
    PolarisBaseEntity P1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL, "P1");
    PolarisBaseEntity P2 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL, "P2");
    this.ensureGrantRecordExists(PR1, P1, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);
    this.ensureGrantRecordExists(PR2, P1, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);
    this.ensureGrantRecordExists(PR2, P2, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);
  }

  void testBrowse() {
    // create test catalog
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    // should see 2 top-level namespaces
    this.validateListReturn(
        List.of(catalog),
        PolarisEntityType.NAMESPACE,
        List.of(
            ImmutablePair.of("N1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("N5", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("N7", PolarisEntitySubType.NULL_SUBTYPE)));

    // should see 3 top-level catalog roles including the admin one
    this.validateListReturn(
        List.of(catalog),
        PolarisEntityType.CATALOG_ROLE,
        List.of(
            ImmutablePair.of(
                PolarisEntityConstants.getNameOfCatalogAdminRole(),
                PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("R1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("R2", PolarisEntitySubType.NULL_SUBTYPE)));

    // 2 principals
    this.validateListReturn(
        null,
        PolarisEntityType.PRINCIPAL,
        List.of(
            ImmutablePair.of(
                PolarisEntityConstants.getRootPrincipalName(), PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("P1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("P2", PolarisEntitySubType.NULL_SUBTYPE)));

    // 3 principal roles with the bootstrap service_admin
    this.validateListReturn(
        null,
        PolarisEntityType.PRINCIPAL_ROLE,
        List.of(
            ImmutablePair.of("PR1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("PR2", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of(
                PolarisEntityConstants.getNameOfPrincipalServiceAdminRole(),
                PolarisEntitySubType.NULL_SUBTYPE)));

    // three namespaces under top-level namespace N1
    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    this.validateListReturn(
        List.of(catalog, N1),
        PolarisEntityType.NAMESPACE,
        PolarisEntitySubType.NULL_SUBTYPE,
        List.of(
            ImmutablePair.of("N2", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("N3", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("N4", PolarisEntitySubType.NULL_SUBTYPE)));
    this.validateListReturn(
        List.of(catalog, N1),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ANY_SUBTYPE,
        List.of(ImmutablePair.of("T4", PolarisEntitySubType.ICEBERG_TABLE)));
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");
    this.validateListReturn(
        List.of(catalog, N5),
        PolarisEntityType.NAMESPACE,
        List.of(ImmutablePair.of("N6", PolarisEntitySubType.NULL_SUBTYPE)));

    // two tables and one view under top-level namespace N1_N1
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    // table or view object
    this.validateListReturn(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ANY_SUBTYPE,
        List.of(
            ImmutablePair.of("T1", PolarisEntitySubType.ICEBERG_TABLE),
            ImmutablePair.of("T2", PolarisEntitySubType.ICEBERG_TABLE),
            ImmutablePair.of("V1", PolarisEntitySubType.ICEBERG_VIEW)));
    // table object only
    this.validateListReturn(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_TABLE,
        List.of(
            ImmutablePair.of("T1", PolarisEntitySubType.ICEBERG_TABLE),
            ImmutablePair.of("T2", PolarisEntitySubType.ICEBERG_TABLE)));
    // view object only
    this.validateListReturn(
        List.of(catalog, N1, N1_N2),
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICEBERG_VIEW,
        List.of(ImmutablePair.of("V1", PolarisEntitySubType.ICEBERG_VIEW)));
    // list all principals
    this.validateListReturn(
        null,
        PolarisEntityType.PRINCIPAL,
        List.of(
            ImmutablePair.of("root", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("P1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("P2", PolarisEntitySubType.NULL_SUBTYPE)));
    // list all principal roles
    this.validateListReturn(
        null,
        PolarisEntityType.PRINCIPAL_ROLE,
        List.of(
            ImmutablePair.of(
                PolarisEntityConstants.getNameOfPrincipalServiceAdminRole(),
                PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("PR1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("PR2", PolarisEntitySubType.NULL_SUBTYPE)));

    // list 2 policies under N7_N8
    PolarisBaseEntity N7 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N7");
    PolarisBaseEntity N7_N8 =
        this.ensureExistsByName(List.of(catalog, N7), PolarisEntityType.NAMESPACE, "N8");
    this.validateListReturn(
        List.of(catalog, N7, N7_N8),
        PolarisEntityType.POLICY,
        List.of(
            ImmutablePair.of("POL1", PolarisEntitySubType.NULL_SUBTYPE),
            ImmutablePair.of("POL2", PolarisEntitySubType.NULL_SUBTYPE)));
  }

  /** Test that entity updates works well */
  void testUpdateEntities() {
    // create test catalog
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    // find table N5/N6/T6
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");
    PolarisBaseEntity N5_N6 =
        this.ensureExistsByName(List.of(catalog, N5), PolarisEntityType.NAMESPACE, "N6");
    PolarisBaseEntity T6v1 =
        this.ensureExistsByName(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T6");
    Assertions.assertThat(T6v1).isNotNull();

    // update the entity
    PolarisBaseEntity T6v2 =
        this.updateEntity(
            List.of(catalog, N5, N5_N6),
            T6v1,
            "{\"v2property\": \"some value\"}",
            "{\"v2internal_property\": \"some other value\"}");
    Assertions.assertThat(T6v2).isNotNull();

    // update it again
    PolarisBaseEntity T6v3 =
        this.updateEntity(
            List.of(catalog, N5, N5_N6),
            T6v2,
            "{\"v3property\": \"some value\"}",
            "{\"v3internal_property\": \"some other value\"}");
    Assertions.assertThat(T6v3).isNotNull();

    // now simulate concurrency issue where another thread tries to update T2v2 again. This should
    // not be updated
    PolarisBaseEntity T6v3p =
        this.updateEntity(
            List.of(catalog, N5, N5_N6),
            T6v2,
            "{\"v3pproperty\": \"some value\"}",
            "{\"v3pinternal_property\": \"some other value\"}");
    Assertions.assertThat(T6v3p).isNull();

    // update an entity which does not exist
    PolarisBaseEntity T5v1 =
        this.ensureExistsByName(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T5");
    var newId = new PolarisBaseEntity.Builder(T5v1).id(100000L).build();
    PolarisBaseEntity notExists =
        this.updateEntity(
            List.of(catalog, N5, N5_N6),
            newId,
            "{\"v3pproperty\": \"some value\"}",
            "{\"v3pinternal_property\": \"some other value\"}");
    Assertions.assertThat(notExists).isNull();
  }

  /** Test that dropping entities works well */
  void testDropEntities() {
    // create test catalog
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    // find namespace N1/N2
    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");

    // attempt to drop the N1/N2 namespace. Will fail because not empty
    this.dropEntity(List.of(catalog, N1), N1_N2);

    // attempt to drop the N1/N4 namespace. Will succeed because empty
    PolarisBaseEntity N1_N4 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N4");
    this.dropEntity(List.of(catalog, N1), N1_N4);

    // find table N5/N6/T6
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");
    PolarisBaseEntity N5_N6 =
        this.ensureExistsByName(List.of(catalog, N5), PolarisEntityType.NAMESPACE, "N6");
    PolarisBaseEntity T6 =
        this.ensureExistsByName(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T6");
    Assertions.assertThat(T6).isNotNull();

    // drop table N5/N6/T6
    this.dropEntity(List.of(catalog, N5, N5_N6), T6);

    // drop the catalog role R2
    PolarisBaseEntity R2 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R2");
    this.dropEntity(List.of(catalog), R2);

    // attempt to drop the entire catalog, should not work since not empty
    this.dropEntity(null, catalog);

    // now drop everything
    PolarisBaseEntity T1 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T1");
    this.dropEntity(List.of(catalog, N1, N1_N2), T1);
    PolarisBaseEntity T2 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T2");
    this.dropEntity(List.of(catalog, N1, N1_N2), T2);
    PolarisBaseEntity V1 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_VIEW,
            "V1");
    this.dropEntity(List.of(catalog, N1, N1_N2), V1);
    this.dropEntity(List.of(catalog, N1), N1_N2);

    PolarisBaseEntity N1_N3 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N3");
    PolarisBaseEntity T3 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N3),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T3");
    this.dropEntity(List.of(catalog, N1, N1_N3), T3);
    PolarisBaseEntity V2 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N3),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_VIEW,
            "V2");
    this.dropEntity(List.of(catalog, N1, N1_N3), V2);
    this.dropEntity(List.of(catalog, N1), N1_N3);

    PolarisBaseEntity T4 =
        this.ensureExistsByName(
            List.of(catalog, N1),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T4");
    this.dropEntity(List.of(catalog, N1), T4);
    this.dropEntity(List.of(catalog), N1);

    PolarisBaseEntity T5 =
        this.ensureExistsByName(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T5");
    this.dropEntity(List.of(catalog, N5, N5_N6), T5);
    this.dropEntity(List.of(catalog, N5), N5_N6);
    this.dropEntity(List.of(catalog), N5);

    PolarisBaseEntity N7 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N7");
    PolarisBaseEntity N7_N8 =
        this.ensureExistsByName(
            List.of(catalog, N7),
            PolarisEntityType.NAMESPACE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "N8");
    PolarisBaseEntity POL1 =
        this.ensureExistsByName(List.of(catalog, N7, N7_N8), PolarisEntityType.POLICY, "POL1");
    PolarisBaseEntity POL2 =
        this.ensureExistsByName(List.of(catalog, N7, N7_N8), PolarisEntityType.POLICY, "POL2");
    PolarisBaseEntity POL3 =
        this.ensureExistsByName(List.of(catalog, N7), PolarisEntityType.POLICY, "POL3");
    this.dropEntity(List.of(catalog, N7, N7_N8), POL1);
    this.dropEntity(List.of(catalog, N7, N7_N8), POL2);
    this.dropEntity(List.of(catalog, N7), POL3);
    this.dropEntity(List.of(catalog, N7), N7_N8);
    this.dropEntity(List.of(catalog), N7);

    // attempt to drop the catalog again, should fail because of role R1
    this.dropEntity(null, catalog);

    // catalog exists
    EntityResult catalogFound =
        polarisMetaStoreManager.readEntityByName(
            this.polarisCallContext,
            null,
            PolarisEntityType.CATALOG,
            PolarisEntitySubType.NULL_SUBTYPE,
            "test");
    // success and found
    Assertions.assertThat(catalogFound.isSuccess()).isTrue();
    Assertions.assertThat(catalogFound.getEntity()).isNotNull();

    // drop the last role
    PolarisBaseEntity R1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R1");
    this.dropEntity(List.of(catalog), R1);

    // the catalog admin role cannot be dropped
    PolarisBaseEntity CATALOG_ADMIN =
        this.ensureExistsByName(
            List.of(catalog),
            PolarisEntityType.CATALOG_ROLE,
            PolarisEntityConstants.getNameOfCatalogAdminRole());
    this.dropEntity(List.of(catalog), CATALOG_ADMIN);
    // should be found since it is undroppable
    this.ensureExistsByName(
        List.of(catalog),
        PolarisEntityType.CATALOG_ROLE,
        PolarisEntityConstants.getNameOfCatalogAdminRole());

    // drop the catalog, should work now. The CATALOG_ADMIN role will be dropped too
    this.dropEntity(null, catalog);

    // catalog exists?
    catalogFound =
        polarisMetaStoreManager.readEntityByName(
            this.polarisCallContext,
            null,
            PolarisEntityType.CATALOG,
            PolarisEntitySubType.NULL_SUBTYPE,
            "test");
    // success and not found
    Assertions.assertThat(catalogFound.getReturnStatus())
        .isEqualTo(BaseResult.ReturnStatus.ENTITY_NOT_FOUND);

    // drop the principal role PR1
    PolarisBaseEntity PR1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL_ROLE, "PR1");
    this.dropEntity(null, PR1);

    // drop the principal role P1
    PolarisBaseEntity P1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL, "P1");
    this.dropEntity(null, P1);
  }

  /** Test granting/revoking privileges */
  public void testPrivileges() {
    // create test catalog
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    // get catalog role R1
    PolarisBaseEntity R1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R1");

    // get principal role PR1
    PolarisBaseEntity PR1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL_ROLE, "PR1");

    // get principal P1
    PolarisBaseEntity P1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL, "P1");

    // test revoking usage on catalog/principal roles
    this.revokeToGrantee(catalog, R1, PR1, PolarisPrivilege.CATALOG_ROLE_USAGE);
    this.revokeToGrantee(null, PR1, P1, PolarisPrivilege.PRINCIPAL_ROLE_USAGE);

    // remove some privileges
    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");
    PolarisBaseEntity N5_N6 =
        this.ensureExistsByName(
            List.of(catalog, N5),
            PolarisEntityType.NAMESPACE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "N6");
    PolarisBaseEntity N5_N6_T5 =
        this.ensureExistsByName(
            List.of(catalog, N5, N5_N6),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "T5");

    // revoke grants
    this.revokePrivilege(R1, List.of(catalog, N1), N1_N2, PolarisPrivilege.TABLE_READ_DATA);

    // revoke priv from the catalog itself
    this.revokePrivilege(R1, List.of(catalog), catalog, PolarisPrivilege.VIEW_CREATE);

    // revoke privs from securables inside the catalog itself
    this.revokePrivilege(R1, List.of(catalog), N5, PolarisPrivilege.TABLE_LIST);
    this.revokePrivilege(R1, List.of(catalog, N5, N5_N6), N5_N6_T5, PolarisPrivilege.TABLE_DROP);

    // test with some entity ids which are prefixes of other entity ids
    PolarisBaseEntity PR900 =
        this.createEntity(
            null,
            PolarisEntityType.PRINCIPAL_ROLE,
            PolarisEntitySubType.NULL_SUBTYPE,
            "PR900",
            900L);
    PolarisBaseEntity PR9000 =
        this.createEntity(
            null,
            PolarisEntityType.PRINCIPAL_ROLE,
            PolarisEntitySubType.NULL_SUBTYPE,
            "PR9000",
            9000L);

    // assign catalog role to PR9000
    grantToGrantee(catalog, R1, PR9000, PolarisPrivilege.CATALOG_ROLE_USAGE);

    LoadGrantsResult loadGrantsResult =
        polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, PR9000);
    this.validateLoadedGrants(loadGrantsResult, true);
    Assertions.assertThat(loadGrantsResult.getGrantRecords()).hasSize(1);
    Assertions.assertThat(loadGrantsResult.getGrantRecords().get(0).getSecurableCatalogId())
        .isEqualTo(R1.getCatalogId());
    Assertions.assertThat(loadGrantsResult.getGrantRecords().get(0).getSecurableId())
        .isEqualTo(R1.getId());

    loadGrantsResult = polarisMetaStoreManager.loadGrantsToGrantee(this.polarisCallContext, PR900);
    Assertions.assertThat(loadGrantsResult).isNotNull();
    Assertions.assertThat(loadGrantsResult.getGrantRecords()).hasSize(0);
  }

  /**
   * Rename an entity and validate it worked
   *
   * @param catPath catalog path
   * @param entity entity to rename
   * @param newCatPath new catalog path
   * @param newName new name
   */
  public void renameEntity(
      List<PolarisEntityCore> catPath,
      PolarisBaseEntity entity,
      List<PolarisEntityCore> newCatPath,
      String newName) {

    // save old name
    String oldName = entity.getName();

    var updatedInternalProperties = Map.of("key1", "updatedDataForInternalProperties1234");
    var updatedProperties = Map.of("key1", "updatedDataForProperties9876");

    // the renamed entity
    var newEntity =
        new PolarisBaseEntity.Builder(entity)
            .name(newName)
            .internalPropertiesAsMap(updatedInternalProperties)
            .propertiesAsMap(updatedProperties)
            .build();
    PolarisEntity renamedEntityInput = new PolarisEntity(newEntity);

    // check to see if we would have a name conflict
    EntityResult newNameLookup =
        polarisMetaStoreManager.readEntityByName(
            polarisCallContext,
            newCatPath == null ? catPath : newCatPath,
            entity.getType(),
            PolarisEntitySubType.ANY_SUBTYPE,
            newName);

    // rename it
    PolarisBaseEntity renamedEntity =
        polarisMetaStoreManager
            .renameEntity(polarisCallContext, catPath, entity, newCatPath, renamedEntityInput)
            .getEntity();

    // ensure success
    if (newNameLookup.getReturnStatus() == BaseResult.ReturnStatus.ENTITY_NOT_FOUND) {
      Assertions.assertThat(renamedEntity).isNotNull();

      // ensure it exists
      PolarisBaseEntity renamedEntityOut =
          this.ensureExistsByName(
              newCatPath == null ? catPath : newCatPath,
              entity.getType(),
              entity.getSubType(),
              newName);

      // what is returned should be same has what has been loaded
      Assertions.assertThat(renamedEntity).isEqualTo(renamedEntityOut);

      // ensure properties have been updated
      Assertions.assertThat(renamedEntityOut.getInternalPropertiesAsMap())
          .containsAllEntriesOf(updatedInternalProperties);
      Assertions.assertThat(renamedEntityOut.getPropertiesAsMap())
          .containsAllEntriesOf(updatedProperties);

      // ensure the old one is gone
      EntityResult res =
          polarisMetaStoreManager.readEntityByName(
              polarisCallContext, catPath, entity.getType(), entity.getSubType(), oldName);

      // not found
      Assertions.assertThat(res.getReturnStatus())
          .isEqualTo(BaseResult.ReturnStatus.ENTITY_NOT_FOUND);
    } else {
      // cannot rename since the entity exists
      Assertions.assertThat(renamedEntity).isNull();
    }
  }

  /** Play with renaming entities */
  public void testRename() {
    // create test catalog
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    // get catalog role R1 and rename it to R3
    PolarisBaseEntity R1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.CATALOG_ROLE, "R1");

    // rename it to something that exists, should fail
    this.renameEntity(List.of(catalog), R1, List.of(catalog), "R2");

    // rename it to something that exists using null newCatalogPath as shorthand, should fail
    this.renameEntity(List.of(catalog), R1, null, "R2");

    // this one should succeed
    this.renameEntity(List.of(catalog), R1, List.of(catalog), "R3");

    // get principal role PR1 and rename it to PR3
    PolarisBaseEntity PR1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL_ROLE, "PR1");
    // exists => fails
    this.renameEntity(null, PR1, null, "PR2");
    // does not exists => succeeds
    this.renameEntity(null, PR1, null, "PR3");

    // get principal P1 and rename it to P3
    PolarisBaseEntity P1 = this.ensureExistsByName(null, PolarisEntityType.PRINCIPAL, "P1");
    // exists => fails
    this.renameEntity(null, P1, null, "P2");
    // does not exists => succeeds
    this.renameEntity(null, P1, null, "P3");

    // N2 namespace
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");

    // rename N1/N2/T1 to N5/T7
    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    PolarisBaseEntity N1_N3 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N3");
    PolarisBaseEntity N1_N2_T1 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "T1");
    // view with the same name exists, should fail
    this.renameEntity(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1, N1_N2), "V1");
    // table with the same name exists, should fail
    this.renameEntity(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1, N1_N2), "T2");
    // view with the same name exists, should fail
    this.renameEntity(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1, N1_N3), "V2");
    // table with the same name exists, should fail
    this.renameEntity(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1, N1_N3), "T3");

    // this should work, T7 does not exist
    this.renameEntity(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N5), "T7");
  }

  /** Play with looking up entities */
  public void testLookup() {
    // load all principals
    List<EntityNameLookupRecord> principals =
        polarisMetaStoreManager
            .listEntities(
                this.polarisCallContext,
                null,
                PolarisEntityType.PRINCIPAL,
                PolarisEntitySubType.NULL_SUBTYPE,
                PageToken.readEverything())
            .getEntities();

    // ensure not null, one element only
    Assertions.assertThat(principals).isNotNull().hasSize(1);

    // get catalog list information
    EntityNameLookupRecord principalListInfo = principals.get(0);

    PolarisBaseEntity principal =
        this.ensureExistsById(
            null,
            principalListInfo.getId(),
            true,
            PolarisEntityConstants.getRootPrincipalName(),
            PolarisEntityType.PRINCIPAL,
            PolarisEntitySubType.NULL_SUBTYPE);

    this.ensureNotExistsById(
        PolarisEntityConstants.getNullId(), principal.getId(), PolarisEntityType.PRINCIPAL_ROLE);
    this.ensureNotExistsById(
        PolarisEntityConstants.getNullId(), principal.getId(), PolarisEntityType.CATALOG);
    this.ensureNotExistsById(
        PolarisEntityConstants.getNullId(), principal.getId(), PolarisEntityType.CATALOG_ROLE);

    // create new catalog
    PolarisBaseEntity catalog =
        new PolarisBaseEntity(
            PolarisEntityConstants.getNullId(),
            polarisMetaStoreManager.generateNewEntityId(this.polarisCallContext).getId(),
            PolarisEntityType.CATALOG,
            PolarisEntitySubType.NULL_SUBTYPE,
            PolarisEntityConstants.getRootEntityId(),
            "test");
    CreateCatalogResult catalogCreated =
        polarisMetaStoreManager.createCatalog(this.polarisCallContext, catalog, List.of());
    Assertions.assertThat(catalogCreated).isNotNull();
    catalog = catalogCreated.getCatalog();

    // now create all objects
    PolarisBaseEntity N1 = this.createEntity(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.createEntity(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    PolarisBaseEntity T1 =
        this.createEntity(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T1");

    this.ensureNotExistsById(catalog.getId(), T1.getId(), PolarisEntityType.NAMESPACE);
  }

  /** Test the set of functions for the entity cache */
  public void testEntityCache() {
    // create test catalog
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    // load catalog by name
    PolarisBaseEntity TEST =
        this.loadCacheEntryByName(
            PolarisEntityConstants.getNullId(),
            PolarisEntityConstants.getNullId(),
            PolarisEntityType.CATALOG,
            "test");

    // and again by id
    TEST = this.loadCacheEntryById(TEST.getCatalogId(), TEST.getId(), TEST.getType());

    // get namespace N1
    PolarisBaseEntity N1 =
        this.loadCacheEntryByName(TEST.getId(), TEST.getId(), PolarisEntityType.NAMESPACE, "N1");

    // refresh it, nothing changed
    this.refreshCacheEntry(
        N1.getEntityVersion(),
        N1.getGrantRecordsVersion(),
        N1.getType(),
        N1.getCatalogId(),
        N1.getId());

    // now update this N1 entity
    this.updateEntity(List.of(TEST), N1, "{\"v1property\": \"property value\"}", null);

    // get namespace N1
    PolarisBaseEntity N1p =
        this.loadCacheEntryByName(TEST.getId(), TEST.getId(), PolarisEntityType.NAMESPACE, "N1");

    // entity version should have changed
    Assertions.assertThat(N1p.getEntityVersion()).isEqualTo(N1.getEntityVersion() + 1);

    // but not the grant records version
    Assertions.assertThat(N1p.getGrantRecordsVersion()).isEqualTo(N1.getGrantRecordsVersion());

    // refresh it, nothing changed
    this.refreshCacheEntry(
        N1.getEntityVersion(),
        N1.getGrantRecordsVersion(),
        N1.getType(),
        N1.getCatalogId(),
        N1.getId());

    // load role R1
    PolarisBaseEntity R1 =
        this.loadCacheEntryByName(TEST.getId(), TEST.getId(), PolarisEntityType.CATALOG_ROLE, "R1");
    R1 = this.loadCacheEntryById(R1.getCatalogId(), R1.getId(), R1.getType());

    // add a grant record to N1
    this.grantPrivilege(R1, List.of(TEST), N1, PolarisPrivilege.NAMESPACE_FULL_METADATA);

    // get namespace N1 again
    PolarisBaseEntity N1pp =
        this.loadCacheEntryByName(TEST.getId(), TEST.getId(), PolarisEntityType.NAMESPACE, "N1");

    // entity version should not have changed compared to N1p
    Assertions.assertThat(N1pp.getEntityVersion()).isEqualTo(N1p.getEntityVersion());

    // but the grant records version should have
    Assertions.assertThat(N1pp.getGrantRecordsVersion())
        .isEqualTo(N1p.getGrantRecordsVersion() + 1);

    // refresh it, grants should be updated
    this.refreshCacheEntry(
        N1.getEntityVersion(),
        N1.getGrantRecordsVersion(),
        N1.getType(),
        N1.getCatalogId(),
        N1.getId());

    // now validate that load something which does not exist, will also work
    this.loadCacheEntryByName(
        N1.getCatalogId(), N1.getId(), PolarisEntityType.TABLE_LIKE, "do_not_exists", false);
    this.loadCacheEntryById(N1.getCatalogId() + 1000, N1.getId(), N1.getType(), false);

    // refresh a purged entity
    this.refreshCacheEntry(
        1, 1, PolarisEntityType.TABLE_LIKE, N1.getCatalogId() + 1000, N1.getId(), false);
  }

  void testPolicyMapping() {
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    PolarisBaseEntity N5 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N5");

    PolarisBaseEntity N1_N2_T1 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "T1");

    PolicyEntity N1_P1 =
        this.createPolicy(List.of(catalog, N1), "P1", PredefinedPolicyTypes.DATA_COMPACTION);

    PolicyEntity N1_P2 =
        this.createPolicy(List.of(catalog, N1), "P2", PredefinedPolicyTypes.DATA_COMPACTION);

    PolicyEntity N5_P3 =
        this.createPolicy(List.of(catalog, N5), "P3", PredefinedPolicyTypes.METADATA_COMPACTION);
    attachPolicyToTarget(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1), N1_P1);
    // attach a different policy of different inheritable type to the same target, should succeed
    attachPolicyToTarget(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N5), N5_P3);

    // attach a different policy of same inheritable type to the same target, should fail
    PolicyAttachmentResult policyAttachmentResult =
        polarisMetaStoreManager.attachPolicyToEntity(
            polarisCallContext,
            List.of(catalog, N1, N1_N2),
            N1_N2_T1,
            List.of(catalog, N1),
            N1_P2,
            null);

    Assertions.assertThat(policyAttachmentResult.isSuccess()).isFalse();
    Assertions.assertThat(policyAttachmentResult.getReturnStatus())
        .isEqualTo(BaseResult.ReturnStatus.POLICY_MAPPING_OF_SAME_TYPE_ALREADY_EXISTS);

    // Attach the same policy to same target again should succeed and replace the existing one
    attachPolicyToTarget(
        List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1), N1_P1, Map.of("test", "test"));

    LoadPolicyMappingsResult loadPolicyMappingsResult =
        polarisMetaStoreManager.loadPoliciesOnEntityByType(
            polarisCallContext, N1_N2_T1, PredefinedPolicyTypes.DATA_COMPACTION);
    Assertions.assertThat(loadPolicyMappingsResult.isSuccess()).isTrue();
    Assertions.assertThat(loadPolicyMappingsResult.getEntities()).hasSize(1);
    PolicyEntity policyEntity = PolicyEntity.of(loadPolicyMappingsResult.getEntities().get(0));
    Assertions.assertThat(policyEntity.getId()).isEqualTo(N1_P1.getId());
    Assertions.assertThat(policyEntity.getPolicyType())
        .isEqualTo(PredefinedPolicyTypes.DATA_COMPACTION);

    detachPolicyFromTarget(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1), N1_P1);
    detachPolicyFromTarget(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N5), N5_P3);
  }

  void testPolicyMappingCleanup() {
    PolarisBaseEntity catalog = this.createTestCatalog("test");
    Assertions.assertThat(catalog).isNotNull();

    PolarisBaseEntity N1 =
        this.ensureExistsByName(List.of(catalog), PolarisEntityType.NAMESPACE, "N1");
    PolarisBaseEntity N1_N2 =
        this.ensureExistsByName(List.of(catalog, N1), PolarisEntityType.NAMESPACE, "N2");
    PolarisBaseEntity N1_N2_T1 =
        this.ensureExistsByName(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE,
            "T1");

    PolarisBaseEntity N1_N2_T3 =
        this.createEntity(
            List.of(catalog, N1, N1_N2),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICEBERG_TABLE,
            "T3");
    PolicyEntity N1_P1 =
        this.createPolicy(List.of(catalog, N1), "P1", PredefinedPolicyTypes.DATA_COMPACTION);

    PolicyEntity N1_P2 =
        this.createPolicy(List.of(catalog, N1), "P2", PredefinedPolicyTypes.DATA_COMPACTION);

    attachPolicyToTarget(List.of(catalog, N1, N1_N2), N1_N2_T3, List.of(catalog, N1), N1_P1);
    LoadPolicyMappingsResult loadPolicyMappingsResult =
        polarisMetaStoreManager.loadPoliciesOnEntity(polarisCallContext, N1_N2_T3);
    Assertions.assertThat(loadPolicyMappingsResult.isSuccess()).isTrue();
    Assertions.assertThat(loadPolicyMappingsResult.getEntities()).hasSize(1);

    // Drop N1_N2_T1, the corresponding policy mapping should be cleaned-up
    this.dropEntity(List.of(catalog, N1, N1_N2), N1_N2_T3);

    BasePersistence ms = polarisCallContext.getMetaStore();
    Assertions.assertThat(
            ms.loadAllTargetsOnPolicy(
                polarisCallContext, N1_P1.getCatalogId(), N1_P1.getId(), N1_P1.getPolicyTypeCode()))
        .isEmpty();

    attachPolicyToTarget(List.of(catalog, N1, N1_N2), N1_N2_T1, List.of(catalog, N1), N1_P2);

    // Drop N1_P2, the dropEntity helper will have cleanup enabled to detach the policy from all
    // targets
    this.dropEntity(List.of(catalog, N1), N1_P2);
    loadPolicyMappingsResult =
        polarisMetaStoreManager.loadPoliciesOnEntity(polarisCallContext, N1_N2_T1);
    Assertions.assertThat(loadPolicyMappingsResult.isSuccess()).isTrue();
    Assertions.assertThat(loadPolicyMappingsResult.getEntities()).isEmpty();
  }
}
