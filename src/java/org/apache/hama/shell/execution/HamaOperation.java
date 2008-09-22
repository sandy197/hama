/*
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
package org.apache.hama.shell.execution;

import java.io.IOException;

import org.apache.hama.HamaConfiguration;

/** HamaOperation **/
public abstract class HamaOperation {

  HamaConfiguration conf;
  int map, reduce;
  
  public HamaOperation(HamaConfiguration conf) {
    this(conf, 2, 1);
  }
  
  public HamaOperation(HamaConfiguration conf, int map, int reduce) {
    this.conf = conf;
    this.map = map;
    this.reduce = reduce;
  }
  
  public void setMapNum(int mapNum) {
    this.map = mapNum;
  }
  
  public void setReduceNum(int reduceNum) {
    this.reduce = reduceNum;
  }
  
  /**
   * do the operation.
   * 
   * @return Matrix
   * @throws AlgebraOpException
   * @throws IOException
   */
  abstract public Object operate() throws AlgebraOpException, IOException ;
  
}
