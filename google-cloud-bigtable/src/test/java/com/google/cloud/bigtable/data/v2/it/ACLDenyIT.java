/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.data.v2.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.api.core.SettableApiFuture;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.test_helpers.env.TestEnvRule;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ACLDenyIT {
  private String prefix;

  @ClassRule
  public static TestEnvRule testEnvRule = new TestEnvRule();

  @Before
  public void setUp() {
    prefix = UUID.randomUUID().toString();
  }

  @Test
  public void readEmpty() throws Throwable {
    String uniqueKey = prefix + "-readEmpty";

    Query query = Query.create(testEnvRule.env().getTableId()).rowKey(uniqueKey);

    // Sync
    ArrayList<Row> rows = Lists.newArrayList(testEnvRule.env().getDataClient().readRows(query));
    assertThat(rows).isEmpty();

    // Async
    AccumulatingObserver observer = new AccumulatingObserver();
    testEnvRule.env().getDataClient().readRowsAsync(query, observer);
    observer.awaitCompletion();
    assertThat(observer.responses).isEmpty();
  }

  static class AccumulatingObserver implements ResponseObserver<Row> {
    final List<Row> responses = Lists.newArrayList();
    final SettableApiFuture<Void> completionFuture = SettableApiFuture.create();

    void awaitCompletion() throws Throwable {
      try {
        completionFuture.get(10, TimeUnit.MINUTES);
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    }

    @Override
    public void onStart(StreamController controller) {}

    @Override
    public void onResponse(Row row) {
      responses.add(row);
    }

    @Override
    public void onError(Throwable t) {
      completionFuture.setException(t);
    }

    @Override
    public void onComplete() {
      completionFuture.set(null);
    }
  }
}
