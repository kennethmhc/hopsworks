# This file is part of Hopsworks
# Copyright (C) 2020, Logical Clocks AB. All rights reserved
#
# Hopsworks is free software: you can redistribute it and/or modify it under the terms of
# the GNU Affero General Public License as published by the Free Software Foundation,
# either version 3 of the License, or (at your option) any later version.
#
# Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
# without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
# PURPOSE.  See the GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License along with this program.
# If not, see <https://www.gnu.org/licenses/>.

require 'json'

describe "On #{ENV['OS']}" do
  after(:all) { clean_all_test_projects(spec: "featureview_trainingdataset") }

  describe "training dataset" do
    describe "internal" do
      context 'with valid project, featurestore service enabled' do
        before :all do
          with_valid_project
        end

        it "should be able to add a hopsfs training dataset to the featurestore" do
          featurestore_name = get_featurestore_name(@project.id)
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          connector = all_metadata["connector"]
          featureview = all_metadata["featureView"]

          expect(parsed_json.key?("id")).to be true
          expect(parsed_json.key?("featurestoreName")).to be true
          expect(parsed_json.key?("name")).to be true
          expect(parsed_json["creator"].key?("email")).to be true
          expect(parsed_json.key?("location")).to be true
          expect(parsed_json.key?("version")).to be true
          expect(parsed_json.key?("dataFormat")).to be true
          expect(parsed_json.key?("trainingDatasetType")).to be true
          expect(parsed_json.key?("location")).to be true
          expect(parsed_json.key?("inodeId")).to be true
          expect(parsed_json.key?("seed")).to be true
          expect(parsed_json["featurestoreName"] == featurestore_name).to be true
          expect(parsed_json["name"] == "#{featureview['name']}_#{featureview['version']}").to be true
          expect(parsed_json["trainingDatasetType"] == "HOPSFS_TRAINING_DATASET").to be true
          expect(parsed_json["storageConnector"]["id"] == connector.id).to be true
          expect(parsed_json["seed"] == 1234).to be true

          # Make sure the location contains the scheme (hopsfs) and the authority
          uri = URI(parsed_json["location"])
          expect(uri.scheme).to eql("hopsfs")
          # If the port is available we can assume that the IP is as well.
          expect(uri.port).to eql(8020)
        end

        it "should not be able to add a hopsfs training dataset to the featurestore without specifying a data format" do
          all_metadata = create_featureview_training_dataset_from_project(@project, expected_status_code: 400, data_format: "not_exist")
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270057).to be true
        end

        it "should not be able to add a hopsfs training dataset to the featurestore with an invalid version" do
          all_metadata = create_featureview_training_dataset_from_project(@project, expected_status_code: 400, version: -1)
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270058).to be true
        end

        it "should be able to add a new hopsfs training dataset without version to the featurestore" do
          all_metadata = create_featureview_training_dataset_from_project(@project, version: nil)
          parsed_json = all_metadata["response"]
          expect(parsed_json["version"] == 1).to be true
        end

        it "should be able to add a new version of an existing hopsfs training dataset without version to the featurestore" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]

          # add second version
          json_result, _ = create_featureview_training_dataset(@project.id, featureview, connector,
                                                               version: nil)
          parsed_json = JSON.parse(json_result)
          expect_status(201)
          # version should be incremented to 2
          expect(parsed_json["version"] == 2).to be true
        end

        it "should be able to add a hopsfs training dataset to the featurestore with splits" do
          splits = [
            {
              name: "test_split",
              percentage: 0.8
            },
            {
              name: "train_split",
              percentage: 0.2
            }
          ]
          all_metadata = create_featureview_training_dataset_from_project(@project, splits: splits,
                                                                          train_split: "train_split")
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("splits")).to be true
          expect(parsed_json["splits"].length).to be 2
        end

        it "should not be able to add a hopsfs training dataset to the featurestore with a non numeric split percentage" do
          split = [{ name: "train_split", percentage: "wrong" }]
          all_metadata = create_featureview_training_dataset_from_project(@project, expected_status_code: 400, splits: split)
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270099).to be true
        end

        it "should not be able to add a hopsfs training dataset to the featurestore with a illegal split name" do
          split = [{ name: "ILLEGALNAME!!!", percentage: 0.8 }]
          all_metadata = create_featureview_training_dataset_from_project(@project, expected_status_code: 400, splits: split)
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270098).to be true
        end

        it "should not be able to add a hopsfs training dataset to the featurestore with splits of duplicate split
          names" do
          splits = [
            {
              name: "test_split",
              percentage: 0.8
            },
            {
              name: "test_split",
              percentage: 0.2
            }
          ]
          all_metadata = create_featureview_training_dataset_from_project(@project, expected_status_code: 400, splits: splits, train_split: "test_split")
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270106).to be true
        end

        it "should not be able to create a training dataset with the same name and version" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]

          create_featureview_training_dataset(@project.id, featureview, connector, version: parsed_json["version"])
          expect_status(400)
        end

        it "should be able to add a hopsfs training dataset to the featurestore without specifying a hopsfs connector" do
          featurestore_id = get_featurestore_id(@project.id)
          featuregroup_suffix = short_random_id
          query = make_sample_query(@project, featurestore_id, featuregroup_suffix: featuregroup_suffix)
          json_result, _ = create_feature_view(@project.id, featurestore_id, query)
          expect_status(201)
          featureview = JSON.parse(json_result)
          td = create_featureview_training_dataset(@project.id, featureview, nil)
          parsed_json = JSON.parse(td)
          expect(parsed_json["storageConnector"]["name"] == "#{@project['projectname']}_Training_Datasets")
        end

        it "should be able to delete a hopsfs training dataset" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          delete_featureview_training_dataset(@project, featureview, version: parsed_json["version"])
        end

        it "should be able to delete all hopsfs training dataset" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]
          create_featureview_training_dataset(@project.id, featureview, connector, version: nil)

          delete_featureview_training_dataset(@project, featureview)
        end

        it "should be able to delete a hopsfs training dataset (data only)" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          delete_featureview_training_dataset_data_only(@project, featureview, version: parsed_json["version"])
        end

        it "should be able to delete all hopsfs training dataset (data only)" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]
          create_featureview_training_dataset(@project.id, featureview, connector, version: nil)

          delete_featureview_training_dataset_data_only(@project, featureview)
        end

        it "should not be able to update the metadata of a hopsfs training dataset from the featurestore" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          json_data = {
            name: "new_testtrainingdatasetname",
            dataFormat: "petastorm"
          }

          json_result2 = update_featureview_training_dataset_metadata(@project, featureview, parsed_json["version"], json_data)
          parsed_json2 = JSON.parse(json_result2)
          expect_status(200)
          expect(parsed_json2.key?("id")).to be true
          expect(parsed_json2.key?("name")).to be true
          expect(parsed_json2["creator"].key?("email")).to be true
          expect(parsed_json2.key?("location")).to be true
          expect(parsed_json2.key?("version")).to be true
          expect(parsed_json2.key?("dataFormat")).to be true
          expect(parsed_json2.key?("trainingDatasetType")).to be true
          expect(parsed_json2.key?("inodeId")).to be true

          expect(parsed_json2["version"]).to eql(parsed_json["version"])
          # make sure the dataformat didn't change
          expect(parsed_json2["dataFormat"] == "tfrecords").to be true
        end

		it "should not be able to update the name of a training dataset" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          json_data = {
            name: "new_testtrainingdatasetname"
          }

          json_result2 = update_featureview_training_dataset_metadata(@project, featureview, parsed_json["version"], json_data)
          parsed_json2 = JSON.parse(json_result2)
          expect_status(200)

          expect(parsed_json2["version"]).to eql(parsed_json["version"])
          # make sure the name didn't change
          expect(parsed_json2["name"]).to eql(parsed_json["name"])
        end

        it "should be able to update the description of a training dataset" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          json_data = {
            name: "new_testtrainingdatasetname",
            description: "new_testtrainingdatasetdescription"
          }

          json_result2 = update_featureview_training_dataset_metadata(@project, featureview, parsed_json["version"], json_data)
          parsed_json2 = JSON.parse(json_result2)
          expect_status(200)

          expect(parsed_json2["description"]).to eql("new_testtrainingdatasetdescription")
          expect(parsed_json2["version"]).to eql(parsed_json["version"])
          # make sure the name didn't change
          expect(parsed_json2["name"]).to eql(parsed_json["name"])
        end

        it "should be able to get a list of training dataset versions based on the version" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]
          create_featureview_training_dataset(@project.id, featureview, connector, version: nil)

          json_result = get_featureview_training_dataset(@project, featureview)
          expect_status(200)
          parsed_json = JSON.parse(json_result)
          expect(parsed_json["count"]).to eq 2
        end

        it "should be able to get a training dataset based on version" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]
          create_featureview_training_dataset(@project.id, featureview, connector, version: nil)

          json_result = get_featureview_training_dataset(@project, featureview, version: 1)
          parsed_json = JSON.parse(json_result)
          expect(parsed_json['version']).to be 1
          expect(parsed_json['name']).to eq "#{featureview['name']}_#{featureview['version']}"

          json_result = get_featureview_training_dataset(@project, featureview, version: 2)
          parsed_json = JSON.parse(json_result)
          expect(parsed_json['version']).to be 2
          expect(parsed_json['name']).to eq "#{featureview['name']}_#{featureview['version']}"
        end

        it "should be able to attach keywords" do
          # TODO: keyword not implement yet for TD
        end

        it "should fail to attach invalid keywords" do
          # TODO: keyword not implement yet for TD
        end

        it "should be able to remove keyword" do
          # TODO: keyword not implement yet for TD
        end

        it "should be able to create a training dataset without statistics settings to test the defaults" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("statisticsConfig")).to be true
          expect(parsed_json["statisticsConfig"].key?("histograms")).to be true
          expect(parsed_json["statisticsConfig"].key?("correlations")).to be true
          expect(parsed_json["statisticsConfig"].key?("exactUniqueness")).to be true
          expect(parsed_json["statisticsConfig"].key?("enabled")).to be true
          expect(parsed_json["statisticsConfig"].key?("columns")).to be true
          expect(parsed_json["statisticsConfig"]["columns"].length).to eql(0)
          expect(parsed_json["statisticsConfig"]["enabled"]).to be true
          expect(parsed_json["statisticsConfig"]["correlations"]).to be false
          expect(parsed_json["statisticsConfig"]["exactUniqueness"]).to be false
          expect(parsed_json["statisticsConfig"]["histograms"]).to be false
        end

        it "should be able to create a training dataset with statistics settings and retrieve them back" do
          stats_config = { enabled: false, histograms: false, correlations: false, exactUniqueness: false, columns:
            ["a_testfeature"] }
          all_metadata = create_featureview_training_dataset_from_project(@project, statistics_config: stats_config)
          parsed_json = all_metadata["response"]
          expect(parsed_json["statisticsConfig"]["columns"].length).to eql(1)
          expect(parsed_json["statisticsConfig"]["columns"][0]).to eql("a_testfeature")
          expect(parsed_json["statisticsConfig"]["enabled"]).to be false
          expect(parsed_json["statisticsConfig"]["correlations"]).to be false
          expect(parsed_json["statisticsConfig"]["exactUniqueness"]).to be false
          expect(parsed_json["statisticsConfig"]["histograms"]).to be false
        end

        it "should not be possible to add a training dataset with non-existing statistic column" do
          stats_config = { enabled: false, histograms: false, correlations: false, exactUniqueness: false, columns: ["wrongname"] }
          all_metadata = create_featureview_training_dataset_from_project(
            @project, statistics_config: stats_config, expected_status_code: 400)
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"]).to eql(270108)
        end

        it "should be able to update the statistics config of a training dataset" do
          all_metadata = create_featureview_training_dataset_from_project(@project)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          json_data = {
            statisticsConfig: {
              histograms: false,
              correlations: false,
              exactUniqueness: false,
              columns: ["a_testfeature"],
              enabled: false
            }
          }

          json_result2 = update_featureview_training_dataset_stats_config(@project, featureview, parsed_json["version"], json_data)
          parsed_json2 = JSON.parse(json_result2)
          expect_status(200)
          expect(parsed_json2["statisticsConfig"]["columns"].length).to eql(1)
          expect(parsed_json2["statisticsConfig"]["columns"][0]).to eql("a_testfeature")
          expect(parsed_json2["statisticsConfig"]["enabled"]).to be false
          expect(parsed_json2["statisticsConfig"]["correlations"]).to be false
          expect(parsed_json2["statisticsConfig"]["exactUniqueness"]).to be false
          expect(parsed_json2["statisticsConfig"]["histograms"]).to be false
        end
      end
    end

    describe "external" do
      context 'with valid project, s3 connector, and featurestore service enabled' do
        before :all do
          with_valid_project
          with_s3_connector(@project[:id])
        end

        it "should be able to add an external training dataset to the featurestore" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          featureview = all_metadata["featureView"]
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("id")).to be true
          expect(parsed_json.key?("featurestoreName")).to be true
          expect(parsed_json.key?("name")).to be true
          expect(parsed_json["creator"].key?("email")).to be true
          expect(parsed_json.key?("location")).to be true
          expect(parsed_json.key?("version")).to be true
          expect(parsed_json.key?("dataFormat")).to be true
          expect(parsed_json.key?("trainingDatasetType")).to be true
          expect(parsed_json.key?("description")).to be true
          expect(parsed_json.key?("seed")).to be true
          expect(parsed_json["featurestoreName"] == @project.projectname.downcase + "_featurestore").to be true
          expect(parsed_json["name"] == "#{featureview['name']}_#{featureview['version']}").to be true
          expect(parsed_json["trainingDatasetType"] == "EXTERNAL_TRAINING_DATASET").to be true
          expect(parsed_json["storageConnector"]["id"] == connector[:id]).to be true
          expect(parsed_json["seed"] == 1234).to be true
        end

        it "should not be able to add an external training dataset to the featurestore without specifying a s3 connector" do
          featurestore_id = get_featurestore_id(@project.id)
          featuregroup_suffix = short_random_id
          query = make_sample_query(@project, featurestore_id, featuregroup_suffix: featuregroup_suffix)
          json_result, _ = create_feature_view(@project.id, featurestore_id, query)
          expect_status(201)
          featureview = JSON.parse(json_result)
          create_featureview_training_dataset(@project.id, featureview, nil, is_internal: false)
          expect_status(404)
        end

        it "should be able to add an external training dataset to the featurestore with splits" do
          splits = [
            {
              name: "test_split",
              percentage: 0.8
            },
            {
              name: "train_split",
              percentage: 0.2
            }
          ]
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(
            @project, connector: connector, is_internal: false, splits: splits, train_split: "train_split")
          parsed_json = all_metadata["response"]
          expect(parsed_json.key?("splits")).to be true
          expect(parsed_json["splits"].length).to be 2
        end

        it "should not be able to add an external training dataset to the featurestore with a non numeric split percentage" do
          splits = [{ name: "train_split", percentage: "wrong" }]
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(
            @project, connector: connector, is_internal: false, splits: splits, expected_status_code: 400)
          parsed_json = all_metadata["response"]
          expect_status(400)
          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270099).to be true
        end

        it "should not be able to add an external training dataset to the featurestore with splits of
        duplicate split names" do
          splits = [
            {
              name: "test_split",
              percentage: 0.8
            },
            {
              name: "test_split",
              percentage: 0.2
            }
          ]
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(
            @project, connector: connector, is_internal: false, splits: splits, train_split: "test_split", expected_status_code: 400)
          parsed_json = all_metadata["response"]

          expect(parsed_json.key?("errorCode")).to be true
          expect(parsed_json.key?("errorMsg")).to be true
          expect(parsed_json.key?("usrMsg")).to be true
          expect(parsed_json["errorCode"] == 270106).to be true
        end

        it "should be able to delete a training dataset" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          delete_featureview_training_dataset(@project, featureview, version: parsed_json["version"])
        end

        it "should be able to delete all training dataset" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]
          create_featureview_training_dataset(@project.id, featureview, connector, version: nil)

          delete_featureview_training_dataset(@project, featureview)
        end

        it "should be able to delete a training dataset (data only)" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          delete_featureview_training_dataset_data_only(@project, featureview, version: parsed_json["version"])
        end

        it "should be able to delete all training dataset (data only)" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          featureview = all_metadata["featureView"]
          connector = all_metadata["connector"]
          create_featureview_training_dataset(@project.id, featureview, connector, version: nil)

          delete_featureview_training_dataset_data_only(@project, featureview)
        end

        it "should be able to update the metadata (description) of an external training dataset from the featurestore" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          json_data = {
            name: "new_testtrainingdatasetname",
            description: "new_testtrainingdatasetdescription"
          }

          json_result2 = update_featureview_training_dataset_metadata(@project, featureview, parsed_json["version"], json_data)
          parsed_json2 = JSON.parse(json_result2)
          expect_status(200)
          expect(parsed_json2.key?("id")).to be true
          expect(parsed_json2.key?("featurestoreName")).to be true
          expect(parsed_json2.key?("name")).to be true
          expect(parsed_json2["creator"].key?("email")).to be true
          expect(parsed_json2.key?("location")).to be true
          expect(parsed_json2.key?("version")).to be true
          expect(parsed_json2.key?("dataFormat")).to be true
          expect(parsed_json2.key?("trainingDatasetType")).to be true
          expect(parsed_json2.key?("description")).to be true
          expect(parsed_json2["featurestoreName"] == @project.projectname.downcase + "_featurestore").to be true
          expect(parsed_json2["description"] == "new_testtrainingdatasetdescription").to be true
          expect(parsed_json2["trainingDatasetType"] == "EXTERNAL_TRAINING_DATASET").to be true
          expect(parsed_json2["version"]).to eql(parsed_json["version"])
        end

        it "should not be able do change the storage connector" do
          connector_id = get_s3_connector_id
          connector = make_connector_dto(connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]

          json_new_connector, _ = create_s3_connector(@project[:id], featureview["featurestoreId"], access_key: "test", secret_key: "test")
          new_connector = JSON.parse(json_new_connector)

          json_data = {
            name: "new_testtrainingdatasetname",
            storageConnector: {
              id: new_connector['id']
            }
          }

          json_result2 = update_featureview_training_dataset_metadata(@project, featureview, parsed_json["version"], json_data)
          parsed_json2 = JSON.parse(json_result2)
          expect_status(200)

          expect(parsed_json2["version"]).to eql(parsed_json["version"])
          # make sure the name didn't change
          expect(parsed_json2["storageConnector"]["id"]).to be connector_id
        end

        it "should store and return the correct path within the bucket" do
          connector = make_connector_dto(get_s3_connector_id)
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, location: "/inner/location", is_internal: false)
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          expect(parsed_json['location']).to eql("s3://testbucket/inner/location/#{featureview['name']}_#{featureview['version']}_1")
        end

        it "should be able to create a training dataset using ADLS connector" do
          project = get_project
          featurestore_id = get_featurestore_id(project.id)
          connector = create_adls_connector(project.id, featurestore_id)
          connector = { "id": JSON.parse(connector)['id'] }
          all_metadata = create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false, location: "/inner/location/")
          parsed_json = all_metadata["response"]
          featureview = all_metadata["featureView"]
          expect(parsed_json['location']).to eql("abfss://containerName@accountName.dfs.core.windows.net/inner/location/#{featureview['name']}_#{featureview['version']}_1")
        end

        it "should not be able to create a training dataset using a SNOWFLAKE connector" do
          project = get_project
          featurestore_id = get_featurestore_id(project.id)
          connector = create_snowflake_connector(project.id, featurestore_id)
          connector = JSON.parse(connector)
          create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false, expected_status_code: 404)
        end

        it "should not be able to create a training dataset using a REDSHIFT connector" do
          project = get_project
          featurestore_id = get_featurestore_id(project.id)
          connector, _ = create_redshift_connector(project.id, featurestore_id, databasePassword: "pwdf")
          connector = JSON.parse(connector)
          create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false, expected_status_code: 404)
        end

        it "should not be able to create a training dataset using a JDBC connector" do
          project = get_project
          featurestore_id = get_featurestore_id(project.id)
          connector, _ = create_jdbc_connector(project.id, featurestore_id)
          connector = JSON.parse(connector)
          create_featureview_training_dataset_from_project(@project, connector: connector, is_internal: false, expected_status_code: 404)
        end
      end
    end
  end
end
