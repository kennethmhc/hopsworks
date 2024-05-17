/*
 * This file is part of Hopsworks
 * Copyright (C) 2024, Hopsworks AB. All rights reserved
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

package io.hops.hopsworks.common.featurestore.embedding;

import com.logicalclocks.servicediscoverclient.exceptions.ServiceDiscoveryException;
import io.hops.hopsworks.common.opensearch.OpenSearchClient;
import io.hops.hopsworks.common.util.LongRunningHttpRequests;
import io.hops.hopsworks.common.util.Settings;
import io.hops.hopsworks.exceptions.OpenSearchException;
import io.hops.hopsworks.vectordb.OpensearchVectorDatabase;
import io.hops.hopsworks.vectordb.VectorDatabaseException;
import org.opensearch.client.RestHighLevelClient;

import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.DependsOn;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
@DependsOn("OpenSearchClient")
public class OpensearchVectorDatabaseConstrainedRetry extends OpensearchVectorDatabase {

  @EJB
  private LongRunningHttpRequests longRunningHttpRequests;
  @EJB
  private Settings settings;
  @EJB
  private OpenSearchClient openSearchClient;

  @Override
  protected Boolean shouldRetry() {
    return longRunningHttpRequests.get() < settings.getMaxLongRunningHttpRequests();
  }

  @Override
  protected void startRetry() {
    longRunningHttpRequests.increment();
  }

  @Override
  protected void doneRetry() {
    longRunningHttpRequests.decrement();
  }

  @Override
  protected RestHighLevelClient getClient() throws VectorDatabaseException {
    try {
      return openSearchClient.getClient();
    } catch (OpenSearchException | ServiceDiscoveryException e) {
      throw  new VectorDatabaseException("Cannot create opensearch client. " + e.getMessage());
    }
  }

}