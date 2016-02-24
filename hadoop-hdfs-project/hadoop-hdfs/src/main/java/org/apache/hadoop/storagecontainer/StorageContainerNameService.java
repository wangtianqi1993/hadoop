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

package org.apache.hadoop.storagecontainer;

import org.apache.commons.lang.NotImplementedException;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockCollection;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.namenode.CacheManager;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.Namesystem;
import org.apache.hadoop.hdfs.server.namenode.ha.HAContext;
import org.apache.hadoop.ipc.StandbyException;
import org.apache.hadoop.security.AccessControlException;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Namesystem implementation to be used by StorageContainerManager.
 */
public class StorageContainerNameService implements Namesystem {

  private ReentrantReadWriteLock coarseLock = new ReentrantReadWriteLock();
  private volatile boolean serviceRunning = true;

  public void shutdown() {
    serviceRunning = false;
  }

  @Override
  public boolean isRunning() {
    return serviceRunning;
  }

  @Override
  public BlockCollection getBlockCollection(long id) {
    return null;
  }

  @Override
  public void startSecretManagerIfNecessary() {
     throw new NotImplementedException();
  }

  @Override
  public boolean isInSnapshot(long blockCollectionID) {
    return false;
  }

  @Override
  public CacheManager getCacheManager() {
    // Cache Management is not supported
    return null;
  }

  @Override
  public HAContext getHAContext() {
    return null;
  }

  /**
   * @return Whether the namenode is transitioning to active state and is in the
   * middle of the starting active services.
   */
  @Override
  public boolean inTransitionToActive() {
    return false;
  }

  @Override
  public void readLock() {
    coarseLock.readLock().lock();
  }

  @Override
  public void readUnlock() {
    coarseLock.readLock().unlock();
  }

  @Override
  public boolean hasReadLock() {
    return coarseLock.getReadHoldCount() > 0 || hasWriteLock();
  }

  @Override
  public void writeLock() {
    coarseLock.writeLock().lock();
  }

  @Override
  public void writeLockInterruptibly() throws InterruptedException {
    coarseLock.writeLock().lockInterruptibly();
  }

  @Override
  public void writeUnlock() {
    coarseLock.writeLock().unlock();
  }

  @Override
  public boolean hasWriteLock() {
    return coarseLock.isWriteLockedByCurrentThread();
  }

  @Override
  public boolean isInSafeMode() {
    return false;
  }

  @Override
  public boolean isInStartupSafeMode() {
    return false;
  }

}
