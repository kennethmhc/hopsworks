/*
 * This file is part of Hopsworks
 * Copyright (C) 2021, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package io.hops.hopsworks.common.featurestore.featuregroup;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import io.hops.hopsworks.common.featurestore.FeaturestoreConstants;
import io.hops.hopsworks.common.featurestore.embedding.EmbeddingController;
import io.hops.hopsworks.common.featurestore.feature.FeatureGroupFeatureDTO;
import io.hops.hopsworks.common.featurestore.featuregroup.cached.CachedFeaturegroupDTO;
import io.hops.hopsworks.common.featurestore.featuregroup.online.OnlineFeaturegroupController;
import io.hops.hopsworks.common.featurestore.featuregroup.stream.StreamFeatureGroupDTO;
import io.hops.hopsworks.common.featurestore.utils.FeaturestoreInputValidation;
import io.hops.hopsworks.exceptions.FeaturestoreException;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.cached.TimeTravelFormat;
import io.hops.hopsworks.persistence.entity.project.Project;
import io.hops.hopsworks.restutils.RESTCodes;
import io.hops.hopsworks.vectordb.Index;
import io.hops.hopsworks.vectordb.OpensearchVectorDatabase;
import org.apache.commons.lang.StringUtils;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static io.hops.hopsworks.restutils.RESTCodes.FeaturestoreErrorCode.COULD_NOT_CREATE_ONLINE_FEATUREGROUP;
import static io.hops.hopsworks.restutils.RESTCodes.FeaturestoreErrorCode.FEATURE_GROUP_DUPLICATE_FEATURE;

@Stateless
@TransactionAttribute(TransactionAttributeType.NEVER)
public class FeatureGroupInputValidation {
  
  @EJB
  protected FeaturestoreInputValidation featureStoreInputValidation;
  @EJB
  protected OnlineFeaturegroupController onlineFeaturegroupController;
  @EJB
  protected EmbeddingController embeddingController;
  
  public FeatureGroupInputValidation() {
  }
  
  // for testing
  public FeatureGroupInputValidation(FeaturestoreInputValidation featureStoreInputValidation,
      OnlineFeaturegroupController onlineFeaturegroupController,
      EmbeddingController embeddingController) {
    this.featureStoreInputValidation = featureStoreInputValidation;
    this.onlineFeaturegroupController = onlineFeaturegroupController;
    this.embeddingController = embeddingController;
  }
  
  /**
   * Verify entity names input by the user for creation of entities in the featurestore
   *
   * @param featuregroupDTO the user input data for the entity
   * @throws FeaturestoreException
   */
  public void verifyUserInput(FeaturegroupDTO featuregroupDTO)
    throws FeaturestoreException {
    featureStoreInputValidation.verifyUserInput(featuregroupDTO);
    
    // features
    verifyFeatureGroupFeatureList(featuregroupDTO.getFeatures());
  }

  /**
   * Verifies the user input feature list for a feature group entity
   * @param featureGroupFeatureDTOS the feature list to verify
   */
  public void verifyFeatureGroupFeatureList(List<FeatureGroupFeatureDTO> featureGroupFeatureDTOS)
    throws FeaturestoreException {
    if (featureGroupFeatureDTOS != null && !featureGroupFeatureDTOS.isEmpty()) {
      for (FeatureGroupFeatureDTO featureGroupFeatureDTO : featureGroupFeatureDTOS) {
        featureStoreInputValidation.nameValidation(featureGroupFeatureDTO.getName());
        featureStoreInputValidation.descriptionValidation(featureGroupFeatureDTO.getName(),
          featureGroupFeatureDTO.getDescription());
        verifyOfflineFeatureType(featureGroupFeatureDTO);
      }
    }
  }
  
  public void verifyOfflineFeatureType(FeatureGroupFeatureDTO feature) throws FeaturestoreException {
    if (Strings.isNullOrEmpty(feature.getType())) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_OFFLINE_TYPE_NOT_PROVIDED, Level.FINE,
        "There was no offline type provided for feature `" + feature.getName() + "`. Offline types " +
          "are mandatory.");
    }
  }

  public void verifyEventTimeFeature(String name, List<FeatureGroupFeatureDTO> features) throws
    FeaturestoreException {
    // no event_time specified, skip validation
    if (name == null) return;
    Optional<FeatureGroupFeatureDTO> eventTimeFeature =
      features.stream().filter(feature -> feature.getName().equalsIgnoreCase(name)).findAny();
    if (eventTimeFeature.isPresent()) {
      if (!FeaturestoreConstants.EVENT_TIME_FEATURE_TYPES.contains(eventTimeFeature.get().getType().toUpperCase())) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.ILLEGAL_EVENT_TIME_FEATURE_TYPE, Level.FINE,
          ", the provided event time feature `" + name + "` is of type `" + eventTimeFeature.get().getType() + "` " +
            "but can only be one of the following types: " + FeaturestoreConstants.EVENT_TIME_FEATURE_TYPES + ".");
      }
    } else {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.EVENT_TIME_FEATURE_NOT_FOUND, Level.FINE, ", " +
        "the provided event time feature `" + name + "` was not found among the available features: " +
        features.stream().map(FeatureGroupFeatureDTO::getName).collect(Collectors.joining(", ")) + ".");
    }
  }
  
  /**
   * Make sure feature schema exists if online is enabled.
   * @param featuregroupDTO
   * @throws FeaturestoreException
   */
  public void verifySchemaProvided(FeaturegroupDTO featuregroupDTO) throws FeaturestoreException{
    if (featuregroupDTO instanceof CachedFeaturegroupDTO
      && ((CachedFeaturegroupDTO) featuregroupDTO).getOnlineEnabled()
      && featuregroupDTO.getFeatures().isEmpty()) {
      throw new FeaturestoreException(
        COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
        Level.SEVERE,
        "Cannot create an online feature group without a feature schema.");
    }
  }

  /**
   * Make sure all features are unique.
   * @param featureGroupDTO
   * @throws FeaturestoreException
   */
  public void verifyNoDuplicatedFeatures(FeaturegroupDTO featureGroupDTO)
      throws FeaturestoreException {
    List<String> duplicates = featureGroupDTO.getFeatures()
        .stream()
        .collect(Collectors.groupingBy(FeatureGroupFeatureDTO::getName, Collectors.counting()))
        .entrySet()
        .stream()
        .filter(e -> e.getValue() > 1)
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    if (!duplicates.isEmpty()) {
      throw new FeaturestoreException(
          FEATURE_GROUP_DUPLICATE_FEATURE,
          Level.SEVERE,
          String.format("Cannot create feature group as there are duplicated feature names: %s",
              StringUtils.join(duplicates, ", ")));
    }
  }
  
  /**
   * Make sure online and offline types match.
   * @param featuregroupDTO
   * @throws FeaturestoreException
   */
  public void verifyOnlineOfflineTypeMatch(FeaturegroupDTO featuregroupDTO) throws FeaturestoreException{
    if (featuregroupDTO.getOnlineEnabled()) {
      // Users cannot specify the online type for embedding fg
      if (featuregroupDTO.getEmbeddingIndex() != null) {
        return;
      }
      for (FeatureGroupFeatureDTO feature : featuregroupDTO.getFeatures()) {
        String offlineType = feature.getType().toLowerCase().replace(" ", "");
        String onlineType =
          onlineFeaturegroupController.getOnlineType(feature).toLowerCase().replace(" ", "");
        
        if (offlineType.equals(onlineType)) {
          continue;
        }
  
        if (offlineType.equals("int") &&
          (onlineType.equals("tinyint") ||
            onlineType.equals("smallint"))) {
          continue;
        }
        
        if (offlineType.equals("boolean") &&
          onlineType.equals("tinyint")) {
          continue;
        }
  
        if (offlineType.equals("string") &&
                (onlineType.startsWith("varchar") ||
                  onlineType.equals("text") ||
                  onlineType.equals("mediumtext") ||
                  onlineType.equals("longtext"))) {
          continue;
        }

        if ((offlineType.startsWith("array") ||
                offlineType.startsWith("struct") ||
                offlineType.startsWith("binary") ||
                offlineType.startsWith("map")) &&
                (onlineType.startsWith("varbinary") ||
                        onlineType.equals("blob") ||
                        onlineType.equals("mediumblob") ||
                        onlineType.equals("longblob"))) {
          continue;
        }
        
        throw new FeaturestoreException(
          COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
          Level.SEVERE,
          "Cannot create an online feature group because " +
            "offline and online types are not compatible. " +
            "Feature: " + feature.getName() + " " +
            "(offline type '" + offlineType +
            "', online type '" + onlineType + "')");
      }
    }
  }
  
  /**
   * Make sure online schema is supported by mysql.
   * @param featuregroupDTO
   * @throws FeaturestoreException
   */
  public void verifyOnlineSchemaValid(FeaturegroupDTO featuregroupDTO) throws FeaturestoreException{
    if (featuregroupDTO.getOnlineEnabled() && featuregroupDTO.getEmbeddingIndex() == null) {
      if (featuregroupDTO.getFeatures().size() > FeaturestoreConstants.MAX_MYSQL_COLUMNS) {
        throw new FeaturestoreException(
          COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
          Level.SEVERE,
          "Cannot create an online feature group because it contains > " +
            FeaturestoreConstants.MAX_MYSQL_COLUMNS + " columns (provided: " +
            featuregroupDTO.getFeatures().size() + " columns).");
      }
      
      Integer totalBytes = 0;
      for (FeatureGroupFeatureDTO feature : featuregroupDTO.getFeatures()) {
        String onlineType =
          onlineFeaturegroupController.getOnlineType(feature).toLowerCase().replace(" ", "");
        totalBytes += estimateOnlineSize(onlineType);
      }
  
      if (totalBytes > FeaturestoreConstants.MAX_MYSQL_COLUMN_SIZE) {
        throw new FeaturestoreException(
          COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
          Level.SEVERE,
          "Cannot create an online feature group because row size > " +
            FeaturestoreConstants.MAX_MYSQL_COLUMN_SIZE + " bytes (estimated size: " + totalBytes +
            " bytes).");
      }
      
    }
  }
  
  /**
   * Make sure primary keys are supported.
   * @param featuregroupDTO
   * @throws FeaturestoreException
   */
  public void verifyPrimaryKeySupported(FeaturegroupDTO featuregroupDTO) throws FeaturestoreException{
    if (featuregroupDTO.getOnlineEnabled() && featuregroupDTO.getEmbeddingIndex() == null) {
      Integer totalBytes = 0;
      for (FeatureGroupFeatureDTO feature : featuregroupDTO.getFeatures()) {
        if (feature.getPrimary()) {
          String pkType =
            onlineFeaturegroupController.getOnlineType(feature).toLowerCase().replace(" ", "");
  
          Boolean found = false;
          for (String supportedName : FeaturestoreConstants.SUPPORTED_MYSQL_PRIMARY_KEYS) {
            if (pkType.startsWith(supportedName.toLowerCase())) {
              found = true;
              break;
            }
          }
  
          totalBytes += estimateOnlineSize(pkType);
          
          if (!found) {
            throw new FeaturestoreException(
              COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
              Level.SEVERE,
              "Cannot create an online feature group because primary key type is not supported. " +
                "Feature: " + feature.getName() + " " +
                "(offline type '" + feature.getType() +
                "', online type '" + onlineFeaturegroupController.getOnlineType(feature) + "')");
          }
        }
      }
      
      if (totalBytes > FeaturestoreConstants.MAX_MYSQL_PRIMARY_KEY_SIZE) {
        throw new FeaturestoreException(
          COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
          Level.SEVERE,
          "Cannot create an online feature group because primary key is > " +
            FeaturestoreConstants.MAX_MYSQL_PRIMARY_KEY_SIZE + " bytes (estimated size: " +
            totalBytes + " bytes).");
      }
      
    }
  }
  
  private Integer estimateOnlineSize(String onlineFeatureType) {
    // conservative estimate of byte size in MySQL
    if (onlineFeatureType.equals("tinyint")) {
      return 1;
    } else if (onlineFeatureType.equals("smallint")) {
      return 2;
    } else if (onlineFeatureType.equals("int")) {
      return 4;
    } else if (onlineFeatureType.equals("float")) {
      return 4;
    } else if (onlineFeatureType.equals("bigint")) {
      return 8;
    } else if (onlineFeatureType.equals("double")) {
      return 8;
    } else if (onlineFeatureType.startsWith("decimal")) {
      return 16;
    } else if (onlineFeatureType.equals("blob") || onlineFeatureType.equals("text")) {
      return 256;
    } else if (onlineFeatureType.startsWith("varchar") && onlineFeatureType.contains("latin1")) {
      return Integer.parseInt(StringUtils.substringBetween(onlineFeatureType, "(", ")"));
    } else if (onlineFeatureType.startsWith("varchar")) {
      return Integer.parseInt(StringUtils.substringBetween(onlineFeatureType, "(", ")")) * 4;
    } else if (onlineFeatureType.startsWith("varbinary")) {
      // 1.4 factor to account for metadata stored alongside varbinary
      return Math.round(Integer.parseInt(onlineFeatureType.replace("varbinary(", "").replace(")",
        "")) * 1.4f);
    }
    
    // default
    return 8;
  }

  /**
   * Make sure partition keys are supported.
   * @param featuregroupDTO
   * @throws FeaturestoreException
   */
  public void verifyPartitionKeySupported(FeaturegroupDTO featuregroupDTO) throws FeaturestoreException{
    if ((featuregroupDTO instanceof CachedFeaturegroupDTO
            && ((CachedFeaturegroupDTO) featuregroupDTO).getTimeTravelFormat() == TimeTravelFormat.HUDI) ||
            (featuregroupDTO instanceof StreamFeatureGroupDTO)) {
      for (FeatureGroupFeatureDTO feature : featuregroupDTO.getFeatures()) {
        if (feature.getPartition()) {
          String pkType = feature.getType().toLowerCase().replace(" ", "");

          Boolean found = false;
          for (String supportedName : FeaturestoreConstants.SUPPORTED_HUDI_PARTITION_KEYS) {
            if (pkType.startsWith(supportedName.toLowerCase())) {
              found = true;
              break;
            }
          }

          if (!found) {
            throw new FeaturestoreException(
                    COULD_NOT_CREATE_ONLINE_FEATUREGROUP,
                    Level.SEVERE,
                    "Cannot create an online feature group because partition key type is not supported. " +
                            "Feature: " + feature.getName() + " " +
                            "(offline type '" + feature.getType() + "')");
          }
        }
      }
    }
  }

  public List<FeatureGroupFeatureDTO> verifyAndGetNewFeatures(List<FeatureGroupFeatureDTO> previousSchema,
    List<FeatureGroupFeatureDTO> newSchema)
    throws FeaturestoreException {
    List<FeatureGroupFeatureDTO> newFeatures = new ArrayList<>();
    for (FeatureGroupFeatureDTO newFeature : newSchema) {
      boolean isNew =
        !previousSchema.stream().anyMatch(previousFeature -> previousFeature.getName().equals(newFeature.getName()));
      if (isNew) {
        newFeatures.add(newFeature);
        if (newFeature.getPrimary() || newFeature.getPartition()) {
          throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.ILLEGAL_FEATUREGROUP_UPDATE, Level.FINE,
            "Appended feature `" + newFeature.getName() + "` is specified as primary or partition key. Primary key and "
              + "partition key cannot be changed when appending features.");
        }
        if (newFeature.getType() == null) {
          throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.ILLEGAL_FEATUREGROUP_UPDATE, Level.FINE,
            "Appended feature `" + newFeature.getName() + "` is missing type information. Type information is " +
              "mandatory when appending features to a feature group.");
        }
      }
    }
    return newFeatures;
  }

  public void verifyEmbeddingFeatureExist(FeaturegroupDTO featureGroupDTO) throws FeaturestoreException {
    Set<String> features =
        featureGroupDTO.getFeatures().stream().map(FeatureGroupFeatureDTO::getName).collect(Collectors.toSet());
    if (featureGroupDTO.getEmbeddingIndex() != null) {
      for (EmbeddingFeatureDTO embeddingFeatureDTO : featureGroupDTO.getEmbeddingIndex().getFeatures()) {
        if (!features.contains(embeddingFeatureDTO.getName())) {
          throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.EMBEDDING_FEATURE_NOT_FOUND, Level.FINE,
              String.format("Provided embedding index `%s` does not exist in the feature group.",
                  embeddingFeatureDTO.getName()));
        }
      }
    }
  }

  public void verifyEmbeddingIndexNotExist(Project project, FeaturegroupDTO featureGroupDTO)
      throws FeaturestoreException {
    if (featureGroupDTO.getEmbeddingIndex() != null) {
      embeddingController.verifyIndexName(project, featureGroupDTO.getEmbeddingIndex().getIndexName());
    }
  }

  public void verifyEmbeddingIndexName(FeaturegroupDTO featureGroupDTO) throws FeaturestoreException {
    if (featureGroupDTO.getEmbeddingIndex() != null && featureGroupDTO.getEmbeddingIndex().getIndexName() != null) {
      String indexName = featureGroupDTO.getEmbeddingIndex().getIndexName();
      String errorMessage = String.format("Provided embedding index name `%s` is not valid. It should be "
          + "1. All letters must be lowercase."
          + "2. Index names cannot begin with _ or -."
          + "3. Index names can't contain specified special characters.", indexName);
      // https://docs.aws.amazon.com/opensearch-service/latest/developerguide/indexing.html
      // Rule 1: All letters must be lowercase.
      if (!indexName.equals(indexName.toLowerCase())) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.INVALID_EMBEDDING_INDEX_NAME, Level.FINE,
            errorMessage);
      }

      // Rule 2: Index names cannot begin with _ or -.
      if (indexName.startsWith("_") || indexName.startsWith("-")) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.INVALID_EMBEDDING_INDEX_NAME, Level.FINE,
            errorMessage);
      }

      // Rule 3: Index names can't contain specified special characters.
      String[] forbiddenChars = {" ", ",", ":", "\"", "*", "+", "/", "\\", "|", "?", "#", ">", "<"};
      for (String forbiddenChar : forbiddenChars) {
        if (indexName.contains(forbiddenChar)) {
          throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.INVALID_EMBEDDING_INDEX_NAME, Level.FINE,
              errorMessage);
        }
      }
    }
  }

  public void verifyVectorDatabaseIndexMappingLimit(Project project, FeaturegroupDTO featureGroupDTO,
      Integer numFeatures)
      throws FeaturestoreException {
    if (featureGroupDTO.getEmbeddingIndex() != null) {
      embeddingController.validateWithinMappingLimit(project,
          new Index(featureGroupDTO.getEmbeddingIndex().getIndexName()),
          numFeatures);
    }
  }

  public void verifyVectorDatabaseSupportedDataType(FeaturegroupDTO featureGroupDTO)
      throws FeaturestoreException {
    if (featureGroupDTO.getEmbeddingIndex() != null) {
      verifyVectorDatabaseSupportedDataType(featureGroupDTO.getFeatures());
    }
  }

  public void verifyVectorDatabaseSupportedDataType(List<FeatureGroupFeatureDTO> featureDTOS)
      throws FeaturestoreException {
    Set<FeatureGroupFeatureDTO> unsupportedFeatures =
        featureDTOS.stream().filter(f -> OpensearchVectorDatabase.getDataType(f.getType()) == null)
            .collect(Collectors.toSet());
    if (unsupportedFeatures.size() > 0) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.VECTOR_DATABASE_DATA_TYPE_NOT_SUPPORTED,
          Level.FINE, "Vector database does not support data type in the following features: " + Joiner.on(", ")
          .join(unsupportedFeatures.stream().map(f -> f.getName() + ": " + f.getType()).collect(
              Collectors.toSet())));
    }
  }
}
