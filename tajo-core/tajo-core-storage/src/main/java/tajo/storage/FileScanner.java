/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tajo.storage;

import org.apache.hadoop.conf.Configuration;
import tajo.catalog.Column;
import tajo.catalog.Schema;
import tajo.catalog.TableMeta;

import java.io.IOException;

public abstract class FileScanner implements Scanner {
  protected boolean inited = false;
  protected final Configuration conf;
  protected final TableMeta meta;
  protected final Schema schema;
  protected final Fragment fragment;

  protected Column [] targets;
  
  public FileScanner(Configuration conf, final TableMeta meta, final Fragment fragment) {
    this.conf = conf;
    this.meta = meta;
    this.schema = meta.getSchema();
    this.fragment = fragment;
  }

  public void init() throws IOException {
    inited = true;
  }

  @Override
  public Schema getSchema() {
    return schema;
  }

  @Override
  public void setTarget(Column[] targets) {
    if (inited) {
      throw new IllegalStateException("Should be called before init()");
    }
    this.targets = targets;
  }

  public void setSearchCondition(Object expr) {
    if (inited) {
      throw new IllegalStateException("Should be called before init()");
    }
  }
}
