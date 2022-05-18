# This file is part of Hopsworks
# Copyright (C) 2022, Logical Clocks AB. All rights reserved
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
  after(:all) { clean_all_test_projects(spec: "featureviewquery") }

  describe "feature view query" do
    describe "internal" do
      context 'with valid project, featurestore service enabled' do
        before :all do
          with_valid_project
        end

        it "should be able to create batch query" do
          featurestore_id = get_featurestore_id(@project.id)
          featurestore_name = get_featurestore_name(@project.id)
          featuregroup_suffix = short_random_id
          project_name = @project.projectname.downcase
          query = make_sample_query(@project, featurestore_id, featuregroup_suffix: featuregroup_suffix)
          json_result, _ = create_feature_view(@project.id, featurestore_id, query)
          parsed_json = JSON.parse(json_result)
          expect_status(201)

          feature_view_name = parsed_json["name"]
          feature_view_version = parsed_json["version"]
          query_result = get "#{ENV['HOPSWORKS_API']}/project/#{@project.id}/featurestores/#{featurestore_id}/featureview/#{feature_view_name}/version/#{feature_view_version}/query/batch?start_time=1234&end_time=4321"
          expect_status_details(200)

          parsed_query_result = JSON.parse(query_result)
          expect(parsed_query_result["featureStoreId"]).to eql(featurestore_id)
          expect(parsed_query_result["featureStoreName"]).to eql(featurestore_name)
          expect(parsed_query_result["leftFeatureGroup"]["id"]).to eql(query[:leftFeatureGroup][:id])
          expect(parsed_query_result["leftFeatures"][0]["name"]).to eql(query[:leftFeatures][0][:name])
          expect(parsed_query_result["leftFeatures"][1]["name"]).to eql(query[:leftFeatures][1][:name])
          expect(parsed_query_result["joins"][0]["query"]["leftFeatureGroup"]["id"]).to eql(query[:joins][0][:query][:leftFeatureGroup][:id])
          expect(parsed_query_result["joins"][0]["query"]["leftFeatures"][0]["name"]).to eql(query[:joins][0][:query][:leftFeatures][1][:name])
          # a_testfeature1 > 0
          expect(parsed_query_result["filter"]["leftLogic"]["leftLogic"]["leftFilter"]["feature"]["name"]).to eql(query[:filter][:leftFilter][:feature][:name])
          expect(parsed_query_result["filter"]["leftLogic"]["leftLogic"]["leftFilter"]["condition"]).to eql(query[:filter][:leftFilter][:condition])
          expect(parsed_query_result["filter"]["leftLogic"]["leftLogic"]["leftFilter"]["value"]).to eql(query[:filter][:leftFilter][:value])
          # ts <= 1234
          expect(parsed_query_result["filter"]["leftLogic"]["rightLogic"]["leftFilter"]["feature"]["name"]).to eql("ts")
          expect(parsed_query_result["filter"]["leftLogic"]["rightLogic"]["leftFilter"]["condition"]).to eql("GREATER_THAN_OR_EQUAL")
          expect(parsed_query_result["filter"]["leftLogic"]["rightLogic"]["leftFilter"]["value"]).to eql("1234")
          # ts >= 4321
          expect(parsed_query_result["filter"]["rightLogic"]["leftFilter"]["feature"]["name"]).to eql("ts")
          expect(parsed_query_result["filter"]["rightLogic"]["leftFilter"]["condition"]).to eql("LESS_THAN_OR_EQUAL")
          expect(parsed_query_result["filter"]["rightLogic"]["leftFilter"]["value"]).to eql("4321")
        end

        it "should be able to retrieve original query" do
          featurestore_id = get_featurestore_id(@project.id)
          featurestore_name = get_featurestore_name(@project.id)
          project_name = @project.projectname.downcase
          featuregroup_suffix = short_random_id
          query = make_sample_query(@project, featurestore_id, featuregroup_suffix: featuregroup_suffix)
          json_result, _ = create_feature_view(@project.id, featurestore_id, query)
          parsed_json = JSON.parse(json_result)
          expect_status(201)

          feature_view_name = parsed_json["name"]
          feature_view_version = parsed_json["version"]
          query_result = get "#{ENV['HOPSWORKS_API']}/project/#{@project.id}/featurestores/#{featurestore_id}/featureview/#{feature_view_name}/version/#{feature_view_version}/query"
          expect_status_details(200)
          parsed_query_result = JSON.parse(query_result)
          expect(parsed_query_result["featureStoreId"]).to eql(featurestore_id)
          expect(parsed_query_result["featureStoreName"]).to eql(featurestore_name)
          expect(parsed_query_result["leftFeatureGroup"]["id"]).to eql(query[:leftFeatureGroup][:id])
          expect(parsed_query_result["leftFeatures"][0]["name"]).to eql(query[:leftFeatures][0][:name])
          expect(parsed_query_result["leftFeatures"][1]["name"]).to eql(query[:leftFeatures][1][:name])
          expect(parsed_query_result["joins"][0]["query"]["leftFeatureGroup"]["id"]).to eql(query[:joins][0][:query][:leftFeatureGroup][:id])
          expect(parsed_query_result["joins"][0]["query"]["leftFeatures"][0]["name"]).to eql(query[:joins][0][:query][:leftFeatures][1][:name])
          expect(parsed_query_result["filter"]["leftFilter"]["feature"]["name"]).to eql(query[:filter][:leftFilter][:feature][:name])
          expect(parsed_query_result["filter"]["leftFilter"]["condition"]).to eql(query[:filter][:leftFilter][:condition])
          expect(parsed_query_result["filter"]["leftFilter"]["value"]).to eql(query[:filter][:leftFilter][:value])
        end

        it "should be able to create batch query using retrieved query" do
          featurestore_id = get_featurestore_id(@project.id)
          featurestore_name = get_featurestore_name(@project.id)
          project_name = @project.projectname.downcase
          featuregroup_suffix = short_random_id
          query = make_sample_query(@project, featurestore_id, featuregroup_suffix: featuregroup_suffix)
          json_result, _ = create_feature_view(@project.id, featurestore_id, query)
          expect_status(201)
          parsed_json = JSON.parse(json_result)

          feature_view_name = parsed_json["name"]
          feature_view_version = parsed_json["version"]
          query_result = get "#{ENV['HOPSWORKS_API']}/project/#{@project.id}/featurestores/#{featurestore_id}/featureview/#{feature_view_name}/version/#{feature_view_version}/query"
          expect_status_details(200)
          parsed_query_result = JSON.parse(query_result)
          json_result, _ = create_feature_view(@project.id, featurestore_id, parsed_query_result)
          expect_status(201)
          parsed_json_new = JSON.parse(json_result)
          feature_view_version_new = parsed_json_new["version"]

          query_result = get "#{ENV['HOPSWORKS_API']}/project/#{@project.id}/featurestores/#{featurestore_id}/featureview/#{feature_view_name}/version/#{feature_view_version_new}/query/batch?start_time=1234&end_time=4321"
          expect_status_details(200)

          parsed_query_result = JSON.parse(query_result)
          expect(parsed_query_result["featureStoreId"]).to eql(featurestore_id)
          expect(parsed_query_result["featureStoreName"]).to eql(featurestore_name)
          expect(parsed_query_result["leftFeatureGroup"]["id"]).to eql(query[:leftFeatureGroup][:id])
          expect(parsed_query_result["leftFeatures"][0]["name"]).to eql(query[:leftFeatures][0][:name])
          expect(parsed_query_result["leftFeatures"][1]["name"]).to eql(query[:leftFeatures][1][:name])
          expect(parsed_query_result["joins"][0]["query"]["leftFeatureGroup"]["id"]).to eql(query[:joins][0][:query][:leftFeatureGroup][:id])
          expect(parsed_query_result["joins"][0]["query"]["leftFeatures"][0]["name"]).to eql(query[:joins][0][:query][:leftFeatures][1][:name])
          # a_testfeature1 > 0
          expect(parsed_query_result["filter"]["leftLogic"]["leftLogic"]["leftFilter"]["feature"]["name"]).to eql(query[:filter][:leftFilter][:feature][:name])
          expect(parsed_query_result["filter"]["leftLogic"]["leftLogic"]["leftFilter"]["condition"]).to eql(query[:filter][:leftFilter][:condition])
          expect(parsed_query_result["filter"]["leftLogic"]["leftLogic"]["leftFilter"]["value"]).to eql(query[:filter][:leftFilter][:value])
          # ts <= 1234
          expect(parsed_query_result["filter"]["leftLogic"]["rightLogic"]["leftFilter"]["feature"]["name"]).to eql("ts")
          expect(parsed_query_result["filter"]["leftLogic"]["rightLogic"]["leftFilter"]["condition"]).to eql("GREATER_THAN_OR_EQUAL")
          expect(parsed_query_result["filter"]["leftLogic"]["rightLogic"]["leftFilter"]["value"]).to eql("1234")
          # ts >= 4321
          expect(parsed_query_result["filter"]["rightLogic"]["leftFilter"]["feature"]["name"]).to eql("ts")
          expect(parsed_query_result["filter"]["rightLogic"]["leftFilter"]["condition"]).to eql("LESS_THAN_OR_EQUAL")
          expect(parsed_query_result["filter"]["rightLogic"]["leftFilter"]["value"]).to eql("4321")
        end
      end
    end
  end
end
