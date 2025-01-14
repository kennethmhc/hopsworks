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

package io.hops.hopsworks.common.featurestore.datavalidation;

import com.google.common.base.Strings;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.hops.hopsworks.common.alert.AlertController;
import io.hops.hopsworks.common.featurestore.activity.FeaturestoreActivityFacade;
import io.hops.hopsworks.common.featurestore.feature.FeatureGroupFeatureDTO;
import io.hops.hopsworks.common.featurestore.featuregroup.ExpectationResult;
import io.hops.hopsworks.common.featurestore.featuregroup.FeaturegroupController;
import io.hops.hopsworks.common.featurestore.featuregroup.ValidationResult;
import io.hops.hopsworks.common.featurestore.featuregroup.cached.FeatureGroupCommitFacade;
import io.hops.hopsworks.common.hdfs.DistributedFileSystemOps;
import io.hops.hopsworks.common.hdfs.DistributedFsService;
import io.hops.hopsworks.common.hdfs.HdfsUsersController;
import io.hops.hopsworks.common.hdfs.inode.InodeController;
import io.hops.hopsworks.common.util.Settings;
import io.hops.hopsworks.exceptions.FeaturestoreException;
import io.hops.hopsworks.persistence.entity.featurestore.Featurestore;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.Featuregroup;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.cached.FeatureGroupCommit;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.Expectation;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.FeatureGroupExpectation;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.FeatureGroupValidation;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.FeatureStoreExpectation;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.FeatureType;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.Level;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.Name;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.Predicate;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.Rule;
import io.hops.hopsworks.persistence.entity.featurestore.featuregroup.datavalidation.ValidationRule;
import io.hops.hopsworks.persistence.entity.project.Project;
import io.hops.hopsworks.persistence.entity.user.Users;
import io.hops.hopsworks.restutils.RESTCodes;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.javatuples.Pair;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.FINE;

@Stateless
@TransactionAttribute(TransactionAttributeType.NEVER)
public class FeatureGroupValidationsController {
  private static final Logger LOGGER = Logger.getLogger(FeatureGroupValidationsController.class.getName());
  private static final String PATH_TO_DATA_VALIDATION = Path.SEPARATOR + Settings.DIR_ROOT + Path.SEPARATOR
          + "%s" + Path.SEPARATOR + Settings.ServiceDataset.DATAVALIDATION.getName();
  private static final String PATH_TO_DATA_VALIDATION_RESULT = PATH_TO_DATA_VALIDATION + Path.SEPARATOR + "%s"
          + Path.SEPARATOR + "%d";

  @EJB
  private DistributedFsService distributedFsService;
  @EJB
  private InodeController inodeController;
  @EJB
  private HdfsUsersController hdfsUsersController;
  @EJB
  private RuleFacade validationRuleFacade;
  @EJB
  private FeatureGroupExpectationFacade featureGroupExpectationFacade;
  @EJB
  private FeatureStoreExpectationFacade featureStoreExpectationFacade;
  @EJB
  private FeatureGroupValidationFacade featureGroupValidationFacade;
  @EJB
  private FeatureGroupCommitFacade featureGroupCommitFacade;
  @EJB
  private FeaturegroupController featuregroupController;
  @EJB
  private FeaturestoreActivityFacade activityFacade;
  @EJB
  private AlertController alertController;

  public Pair<FeatureGroupValidation, List<ExpectationResult>> getFeatureGroupValidationResults(Users user,
    Project project,
    Featuregroup featuregroup, Date validationTime)
    throws FeaturestoreException {
    LOGGER.log(FINE, "Fetching validation result for feature group " +
            featuregroup.getName());
    String hdfsUsername = hdfsUsersController.getHdfsUserName(project, user);
    DistributedFileSystemOps udfso = null;
    try {
      Path path2result = new Path(String.format(PATH_TO_DATA_VALIDATION_RESULT, project.getName(),
        featuregroup.getName(), featuregroup.getVersion()) + Path.SEPARATOR + validationTime.getTime());
      udfso = distributedFsService.getDfsOps(hdfsUsername);
      FileStatus[] validationFile = udfso.getFilesystem().globStatus(path2result);
      if (validationFile == null || validationFile.length == 0) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.DATA_VALIDATION_RESULTS_NOT_FOUND,
                FINE, "feature group: " + featuregroup.getName()
                + ", validation time: " + validationTime.getTime());
      }
      List<ExpectationResult> expectationResults = new ArrayList<>();
      for (FileStatus fileStatus : validationFile) {
        Path resultFile = fileStatus.getPath();
        try (FSDataInputStream inStream = udfso.open(resultFile)) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          IOUtils.copyBytes(inStream, out, 512);
          expectationResults = getValidationsFromFile(out.toString());
        }
      }
      
      FeatureGroupValidation featureGroupValidation =
        featureGroupValidationFacade.findByFeaturegroupAndValidationTime(featuregroup, validationTime).orElseThrow(
          () -> new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.DATA_VALIDATION_NOT_FOUND,
                  FINE, "feature group: " + featuregroup.getName()
                  + ", validation time: " + validationTime));
      
      featureGroupValidation.setStatus(getValidationResultStatus(expectationResults));
      return new Pair<>(featureGroupValidation, expectationResults);
    } catch (IOException ex) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.COULD_NOT_READ_DATA_VALIDATION_RESULT,
        java.util.logging.Level.WARNING, "Failed to read validation result",
        "Failed to read validation result from HDFS for Feature group " + featuregroup.getName(), ex);
    } finally {
      distributedFsService.closeDfsClient(udfso);
    }
  }
  
  private List<ExpectationResult> getValidationsFromFile(String rules) {
    Gson deserializer = new Gson();
    JsonArray topLevelObject = deserializer.fromJson(rules, JsonElement.class).getAsJsonArray();
    JsonArray dataValidationsJson = topLevelObject.getAsJsonArray();
    List<ExpectationResult> results = new ArrayList<>(dataValidationsJson.size());
    dataValidationsJson.forEach(result -> {
      ExpectationResult cg = deserializer.fromJson(result, ExpectationResult.class);
      results.add(cg);
    });
    return results;
  }

  public FeatureGroupValidation putFeatureGroupValidationResults(Users user, Project project,
    Featuregroup featuregroup, List<ExpectationResult> results, Long validationTime) throws FeaturestoreException {
    FeatureGroupValidation.Status status = getValidationResultStatus(results);
    alertController.sendAlert(featuregroup, results, status);
    String hdfsUsername = hdfsUsersController.getHdfsUserName(project, user);
    DistributedFileSystemOps udfso = null;
    try {
      String path2result = String.format(PATH_TO_DATA_VALIDATION_RESULT, project.getName(),
        featuregroup.getName(), featuregroup.getVersion()) + Path.SEPARATOR + validationTime;
      udfso = distributedFsService.getDfsOps(hdfsUsername);
      
      Gson gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();

      try (FSDataOutputStream outStream = udfso.create(path2result)) {
        outStream.writeBytes(gson.toJson(results));
        outStream.hflush();
      }
      Date validationTimeDate = new Timestamp(validationTime);
      FeatureGroupValidation featureGroupValidation =
        new FeatureGroupValidation(validationTimeDate, inodeController.getInodeAtPath(path2result),
                featuregroup, getValidationResultStatus(results));
      featureGroupValidationFacade.persist(featureGroupValidation);

      activityFacade.logValidationActivity(featuregroup, user, featureGroupValidation);
      // Persist validation results but throw an error if the data is invalid so that the client (hsfs) does not insert
      if (featuregroup.getValidationType().getSeverity() < status.getSeverity()) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_GROUP_CHECKS_FAILED,
                FINE,
                "Results: " + results.stream()
                        .filter(result -> result.getStatus().getSeverity()
                                >= FeatureGroupValidation.Status.WARNING.getSeverity())
                        .collect(Collectors.toList()));
      }
      return featureGroupValidation;
    } catch (IOException ex) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.COULD_NOT_READ_DATA_VALIDATION_RESULT,
        java.util.logging.Level.WARNING, "Failed to persist validation results",
        "Failed to persist validation result to HDFS for Feature group " + featuregroup.getName(), ex);
    } finally {
      distributedFsService.closeDfsClient(udfso);
    }
  }
  
  public FeatureGroupExpectation getFeatureGroupExpectation(Featuregroup featuregroup, String name)
    throws FeaturestoreException {
    // Find the FeatureStoreExpectation first
    FeatureStoreExpectation featureStoreExpectation = getFeatureStoreExpectation(featuregroup.getFeaturestore(), name);
    Optional<FeatureGroupExpectation> optional =
      featureGroupExpectationFacade.findByFeaturegroupAndExpectation(featuregroup, featureStoreExpectation);
    if (!optional.isPresent()) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_GROUP_EXPECTATION_NOT_FOUND,
        FINE, "Expectation: " + name);
    }
    return optional.get();
  }

  public FeatureStoreExpectation getFeatureStoreExpectation(Featurestore featurestore, String name)
    throws FeaturestoreException {
    Optional<FeatureStoreExpectation> e = featureStoreExpectationFacade.findByFeaturestoreAndName(featurestore, name);
    if (!e.isPresent()) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_STORE_EXPECTATION_NOT_FOUND,
        FINE, "Expectation: " + name);
    }
    return e.get();
  }

  public ValidationRule getValidationRuleByNameAndPredicate(Name name, Predicate predicate) {
    return validationRuleFacade.findByNameAndPredicate(name, predicate);
  }

  /**
   * If there are no Warnings or Errors then total status is Success.
   *
   * If there are only constraints with severity level Warning, then if
   * (a) there are no errors but at least one Warning, status is Warning
   * (b) there is at least one error, status is Error
   *
   * If there is at least one constraint with severity level Error, then if
   * there is any Warning or Error, final status is Error
   * @param results data validations results
   */
  private FeatureGroupValidation.Status getValidationResultStatus(final List<ExpectationResult> results) {
    int success = 0, warning = 0, error = 0;
    int expectationSuccess = 0, expectationWarning = 0, expectationError = 0;
    Level level = Level.WARNING;
    for (ExpectationResult result : results) {
      for (ValidationResult validationResult : result.getResults()) {
        if (validationResult.getRule().getLevel().equals(Level.ERROR)) {
          level = Level.ERROR;
        }
        // TODO(Theo): Make code more DRY
        switch (validationResult.getStatus()) {
          case SUCCESS:
            success++;
            break;
          case WARNING:
            warning++;
            break;
          case FAILURE:
            error++;
            break;
          default:
        }
      }
      result.setStatus(calculateStatus(success, warning, error, level));
      switch (result.getStatus()) {
        case SUCCESS:
          expectationSuccess++;
          break;
        case WARNING:
          expectationWarning++;
          break;
        case FAILURE:
          expectationError++;
          break;
        default:
      }
    }
    return calculateStatus(expectationSuccess, expectationWarning, expectationError, level);
  }

  private FeatureGroupValidation.Status calculateStatus(int success, int warning, int error, Level level) {
    if (success != 0 && warning == 0 && error == 0) {
      return FeatureGroupValidation.Status.SUCCESS;
    }
    if (level.equals(Level.WARNING)) {
      if (warning > 0 && error == 0) {
        return FeatureGroupValidation.Status.WARNING;
      }
      if (warning >= 0 && error > 0) {
        return FeatureGroupValidation.Status.FAILURE;
      }
    }
    if (level.equals(Level.ERROR)) {
      return FeatureGroupValidation.Status.FAILURE;
    }
    return FeatureGroupValidation.Status.NONE;
  }

  public FeatureStoreExpectation createOrUpdateExpectation(Featurestore featurestore, Expectation expectation)
    throws FeaturestoreException {

    // Some expectation sanity checks
    Set<ValidationRule> validationRules = new HashSet<>();
    for (Rule rule : expectation.getRules()) {
      Optional<ValidationRule> validationRule = validationRuleFacade.findByName(rule.getName());
      if (!validationRule.isPresent()) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_STORE_RULE_NOT_FOUND,
                FINE, "Rule: " + rule.getName());
      }
      // Check that the rule's predicate is set
      if (validationRule.get().getPredicate() == Predicate.FEATURE && Strings.isNullOrEmpty(rule.getFeature()) ||
          validationRule.get().getPredicate() == Predicate.ACCEPTED_TYPE && rule.getAcceptedType() == null ||
          validationRule.get().getPredicate() == Predicate.LEGAL_VALUES && (rule.getLegalValues() == null ||
                                                                            rule.getLegalValues().isEmpty()) ||
          validationRule.get().getPredicate() == Predicate.PATTERN && Strings.isNullOrEmpty(rule.getPattern())) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.VALIDATION_RULE_INCOMPLETE,
                FINE, "Rule: " + rule.getName() + " should set " + validationRule.get().getPredicate());
      }
      if (!rule.getName().isAppliedToFeaturePairs() && rule.getMin() == null && rule.getMax() == null) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.VALIDATION_RULE_INCOMPLETE,
                FINE, "At least one of min/max need to be provided for rule: " + rule.getName());
      } // Min/max is set to 1 by default for compliance rules
      else if (rule.getName().isAppliedToFeaturePairs() && rule.getMin() == null && rule.getMax() == null) {
        rule.setMin(1.0);
        rule.setMax(1.0);
      }
      validationRules.add(validationRule.get());
    }

    // Persist expectation
    FeatureStoreExpectation featureStoreExpectation;
    Optional<FeatureStoreExpectation> e =
      featureStoreExpectationFacade.findByFeaturestoreAndName(featurestore, expectation.getName());
    featureStoreExpectation = e.orElseGet(FeatureStoreExpectation::new);
    featureStoreExpectation.setValidationRules(validationRules);
    featureStoreExpectation.setFeatureStore(featurestore);
    featureStoreExpectation.setName(expectation.getName());
    featureStoreExpectation.setDescription(expectation.getDescription());
    featureStoreExpectation.setExpectation(expectation);
  
    return featureStoreExpectationFacade.merge(featureStoreExpectation);
  }

  public void deleteExpectation(Featurestore featurestore, String name) throws FeaturestoreException {
    featureStoreExpectationFacade.remove(getFeatureStoreExpectation(featurestore, name));
  }

  public FeatureGroupExpectation attachExpectation(Featuregroup featuregroup, String name, Project project, Users user)
    throws FeaturestoreException {
    FeatureStoreExpectation featureStoreExpectation = getFeatureStoreExpectation(featuregroup.getFeaturestore(), name);
    FeatureGroupExpectation featureGroupExpectation;
    Optional<FeatureGroupExpectation> attachedExpectation =
      featureGroupExpectationFacade.findByFeaturegroupAndExpectation(featuregroup, featureStoreExpectation);
    featureValidation(featureStoreExpectation, featuregroup, project, user);

    if (!attachedExpectation.isPresent()) {
      featureGroupExpectation = new FeatureGroupExpectation();
      featureGroupExpectation.setFeaturegroup(featuregroup);
      featureGroupExpectation.setFeatureStoreExpectation(featureStoreExpectation);
      return featureGroupExpectationFacade.merge(featureGroupExpectation);
    } else {
      return attachedExpectation.get();
    }
  }

  public void featureValidation(FeatureStoreExpectation expectation,
                                Featuregroup featuregroup, Project project, Users user)
          throws FeaturestoreException {
    List<FeatureGroupFeatureDTO> featuresDTOs = featuregroupController.getFeatures(featuregroup, project, user);
    featureValidation(Collections.singletonList(expectation), featuresDTOs);
  }

  /**
   * Validate than features exist in the feature group and that the rules can be applied to the features (types).
   *
   * @param featureStoreExpectations featureStoreExpectations
   * @param features features
   * @throws FeaturestoreException FeaturestoreException
   */
  public void featureValidation(List<FeatureStoreExpectation> featureStoreExpectations,
                                List<FeatureGroupFeatureDTO> features)
          throws FeaturestoreException {
    List<String> featureTypesErrMsg = new ArrayList<>();
    List<String> featureNames = new ArrayList<>();

    for (FeatureGroupFeatureDTO featureGroupFeature : features) {
      featureNames.add(featureGroupFeature.getName());
    }

    List<String> featuresNotFound = new ArrayList<>();
    for (FeatureStoreExpectation expectation : featureStoreExpectations) {
      // List to store all the features that do not exist
      for (String feature : expectation.getExpectation().getFeatures()) {
        if (!featureNames.contains(feature)) {
          featuresNotFound.add("Expectation: " + expectation.getExpectation().getName() + ", feature:" + feature);
        }
      }
      if (!featuresNotFound.isEmpty()) {
        throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_GROUP_EXPECTATION_FEATURE_NOT_FOUND,
                FINE, "expectation: " + expectation + ", feature: " + featuresNotFound);
      }
    }

    for (FeatureStoreExpectation expectation : featureStoreExpectations) {
      // Check that all the expectation's features exist in the feature group
      // Check that the data type of rules match the data types of the features
      for (FeatureGroupFeatureDTO featureGroupFeature : features) {
        if (expectation.getExpectation().getFeatures().contains(featureGroupFeature.getName())) {
          featureNames.add(featureGroupFeature.getName());
          for (ValidationRule validationRule : expectation.getValidationRules()) {
            if (validationRule.getFeatureType() != null &&
                    !getRuleTypeMappings(validationRule.getFeatureType(), featureGroupFeature.getType())) {
              featureTypesErrMsg.add("feature: " + featureGroupFeature.getName() + ", " +
                      "expectation: " + expectation.getExpectation().getName() + ", " +
                      "feature type: " + featureGroupFeature.getType() + ", " +
                      "rule: " + validationRule.getName() + ", " +
                      "rule type: " + validationRule.getFeatureType() + ", ");
            }
          }
        }
      }
    }
    if (!featureTypesErrMsg.isEmpty()) {
      throw new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_GROUP_EXPECTATION_FEATURE_TYPE_INVALID,
              FINE, featureTypesErrMsg.toString());
    }
  }

  public void detachExpectation(Featuregroup featuregroup, String name) throws FeaturestoreException {
    FeatureStoreExpectation featureStoreExpectation = getFeatureStoreExpectation(featuregroup.getFeaturestore(), name);
    Optional<FeatureGroupExpectation> e =
            featureGroupExpectationFacade.findByFeaturegroupAndExpectation(featuregroup, featureStoreExpectation);
    featureGroupExpectationFacade.remove(e.orElseThrow( () ->
            new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.FEATURE_GROUP_EXPECTATION_NOT_FOUND,
            FINE, "expectation: " + name)));
  }

  public Long getCommitTime(FeatureGroupValidation featureGroupValidation) {
    return featureGroupCommitFacade.findByValidation(featureGroupValidation)
            .map(FeatureGroupCommit::getCommittedOn)
            .orElse(null);
  }

  private boolean getRuleTypeMappings(FeatureType featureType, String featureGroupFeatureType) {
    // <FeatureType, FeatureGroupFeatureType>
    final Map<FeatureType, List<String>> rulesTypeMappings =  new HashMap<>();
    rulesTypeMappings.put(FeatureType.Numerical,Arrays.asList("float", "double", "decimal", "numeric", "int",
                                                                  "integer", "tinyint", "bigint"));
    rulesTypeMappings.put(FeatureType.Categorical,Arrays.asList("string", "varchar", "char"));

    return rulesTypeMappings.get(featureType)
                             .stream()
                             .anyMatch(numericType -> numericType.toLowerCase()
                                .startsWith(featureGroupFeatureType.toLowerCase()));
  }

}