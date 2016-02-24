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

import com.google.protobuf.BlockingService;
import org.apache.hadoop.ha.HAServiceProtocol;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.protocol.proto.DatanodeProtocolProtos;
import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolServerSideTranslatorPB;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.blockmanagement.BlocksMap;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NodeType;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.Namesystem;
import org.apache.hadoop.hdfs.server.protocol.*;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.WritableRpcEngine;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.storagecontainer.protocol.ContainerLocationProtocol;
import org.apache.hadoop.util.LightWeightGSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import static org.apache.hadoop.hdfs.DFSConfigKeys.*;

/**
 * Service that allocates storage containers and tracks their
 * location.
 */
public class StorageContainerManager
    implements DatanodeProtocol, ContainerLocationProtocol {

  public static final Logger LOG =
      LoggerFactory.getLogger(StorageContainerManager.class);

  private final Namesystem ns = new StorageContainerNameService();
  private final BlockManager blockManager;

  private long txnId = 234;

  /** The RPC server that listens to requests from DataNodes. */
  private final RPC.Server serviceRpcServer;
  private final InetSocketAddress serviceRPCAddress;

  /** The RPC server that listens to requests from clients. */
  private final RPC.Server clientRpcServer;
  private final InetSocketAddress clientRpcAddress;

  public StorageContainerManager(OzoneConfiguration conf)
      throws IOException {
    BlocksMap containerMap = new BlocksMap(
        LightWeightGSet.computeCapacity(2.0, "BlocksMap"),
        new StorageContainerMap());
    this.blockManager = new BlockManager(ns, false, conf, containerMap);

    int handlerCount =
        conf.getInt(DFS_NAMENODE_HANDLER_COUNT_KEY,
            DFS_NAMENODE_HANDLER_COUNT_DEFAULT);

    RPC.setProtocolEngine(conf, DatanodeProtocolPB.class,
        ProtobufRpcEngine.class);

    DatanodeProtocolServerSideTranslatorPB dnProtoPbTranslator =
        new DatanodeProtocolServerSideTranslatorPB(this);
    BlockingService dnProtoPbService =
        DatanodeProtocolProtos.DatanodeProtocolService
            .newReflectiveBlockingService(dnProtoPbTranslator);

    WritableRpcEngine.ensureInitialized();

    InetSocketAddress serviceRpcAddr = NameNode.getServiceAddress(conf, false);
    if (serviceRpcAddr != null) {
      String bindHost =
          conf.getTrimmed(DFS_NAMENODE_SERVICE_RPC_BIND_HOST_KEY);
      if (bindHost == null || bindHost.isEmpty()) {
        bindHost = serviceRpcAddr.getHostName();
      }
      LOG.info("Service RPC server is binding to " + bindHost + ":" +
          serviceRpcAddr.getPort());

      int serviceHandlerCount =
          conf.getInt(DFS_NAMENODE_SERVICE_HANDLER_COUNT_KEY,
              DFS_NAMENODE_SERVICE_HANDLER_COUNT_DEFAULT);
      serviceRpcServer = new RPC.Builder(conf)
          .setProtocol(
              org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolPB.class)
          .setInstance(dnProtoPbService)
          .setBindAddress(bindHost)
          .setPort(serviceRpcAddr.getPort())
          .setNumHandlers(serviceHandlerCount)
          .setVerbose(false)
          .setSecretManager(null)
          .build();

      DFSUtil.addPBProtocol(conf, DatanodeProtocolPB.class, dnProtoPbService,
          serviceRpcServer);

      InetSocketAddress listenAddr = serviceRpcServer.getListenerAddress();
      serviceRPCAddress = new InetSocketAddress(
          serviceRpcAddr.getHostName(), listenAddr.getPort());
      conf.set(DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY,
          NetUtils.getHostPortString(serviceRPCAddress));
    } else {
      serviceRpcServer = null;
      serviceRPCAddress = null;
    }

    InetSocketAddress rpcAddr = DFSUtilClient.getNNAddress(conf);
    String bindHost = conf.getTrimmed(DFS_NAMENODE_RPC_BIND_HOST_KEY);
    if (bindHost == null || bindHost.isEmpty()) {
      bindHost = rpcAddr.getHostName();
    }
    LOG.info("RPC server is binding to " + bindHost + ":" + rpcAddr.getPort());

    clientRpcServer = new RPC.Builder(conf)
        .setProtocol(
            org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolPB.class)
        .setInstance(dnProtoPbService)
        .setBindAddress(bindHost)
        .setPort(rpcAddr.getPort())
        .setNumHandlers(handlerCount)
        .setVerbose(false)
        .setSecretManager(null)
        .build();

    DFSUtil.addPBProtocol(conf, DatanodeProtocolPB.class, dnProtoPbService,
        clientRpcServer);

    // The rpc-server port can be ephemeral... ensure we have the correct info
    InetSocketAddress listenAddr = clientRpcServer.getListenerAddress();
    clientRpcAddress = new InetSocketAddress(
        rpcAddr.getHostName(), listenAddr.getPort());
    conf.set(FS_DEFAULT_NAME_KEY,
        DFSUtilClient.getNNUri(clientRpcAddress).toString());
  }

  @Override
  public DatanodeRegistration registerDatanode(
      DatanodeRegistration registration) throws IOException {
    ns.writeLock();
    try {
      blockManager.getDatanodeManager().registerDatanode(registration);
    } finally {
      ns.writeUnlock();
    }
    return registration;
  }

  @Override
  public HeartbeatResponse sendHeartbeat(DatanodeRegistration registration,
      StorageReport[] reports, long dnCacheCapacity, long dnCacheUsed,
      int xmitsInProgress, int xceiverCount, int failedVolumes,
      VolumeFailureSummary volumeFailureSummary,
               boolean requestFullBlockReportLease) throws IOException {
    ns.readLock();
    try {
      final int maxTransfer = blockManager.getMaxReplicationStreams()
          - xmitsInProgress;
      DatanodeCommand[] cmds = blockManager.getDatanodeManager()
          .handleHeartbeat(registration, reports, blockManager.getBlockPoolId(),
              0, 0, xceiverCount, maxTransfer, failedVolumes,
              volumeFailureSummary);

      return new HeartbeatResponse(cmds,
          new NNHAStatusHeartbeat(HAServiceProtocol.HAServiceState.ACTIVE,
              txnId), null, 0);
    } finally {
      ns.readUnlock();
    }
  }

  @Override
  public DatanodeCommand blockReport(DatanodeRegistration registration,
      String poolId, StorageBlockReport[] reports,
      BlockReportContext context) throws IOException {
    for (int r = 0; r < reports.length; r++) {
      final BlockListAsLongs storageContainerList = reports[r].getBlocks();
      blockManager.processReport(registration, reports[r].getStorage(),
          storageContainerList, context, (r == reports.length - 1));
    }
    return null;
  }

  @Override
  public DatanodeCommand cacheReport(DatanodeRegistration registration,
      String poolId, List<Long> blockIds) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void blockReceivedAndDeleted(DatanodeRegistration registration,
      String poolId, StorageReceivedDeletedBlocks[] rcvdAndDeletedBlocks)
      throws IOException {
    for(StorageReceivedDeletedBlocks r : rcvdAndDeletedBlocks) {
      ns.writeLock();
      try {
        blockManager.processIncrementalBlockReport(registration, r);
      } finally {
        ns.writeUnlock();
      }
    }
  }

  @Override
  public void errorReport(DatanodeRegistration registration,
      int errorCode, String msg) throws IOException {
    String dnName =
        (registration == null) ? "Unknown DataNode" : registration.toString();

    if (errorCode == DatanodeProtocol.NOTIFY) {
      LOG.info("Error report from " + dnName + ": " + msg);
      return;
    }

    if (errorCode == DatanodeProtocol.DISK_ERROR) {
      LOG.warn("Disk error on " + dnName + ": " + msg);
    } else if (errorCode == DatanodeProtocol.FATAL_DISK_ERROR) {
      LOG.warn("Fatal disk error on " + dnName + ": " + msg);
      blockManager.getDatanodeManager().removeDatanode(registration);
    } else {
      LOG.info("Error report from " + dnName + ": " + msg);
    }
  }

  @Override
  public NamespaceInfo versionRequest() throws IOException {
    ns.readLock();
    try {
      return unprotectedGetNamespaceInfo();
    } finally {
      ns.readUnlock();
    }
  }

  private NamespaceInfo unprotectedGetNamespaceInfo() {
    return new NamespaceInfo(1, "random", "random", 2,
        NodeType.STORAGE_CONTAINER_SERVICE);
  }

  @Override
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    // It doesn't make sense to have LocatedBlock in this API.
    ns.writeLock();
    try {
      for (int i = 0; i < blocks.length; i++) {
        ExtendedBlock blk = blocks[i].getBlock();
        DatanodeInfo[] nodes = blocks[i].getLocations();
        String[] storageIDs = blocks[i].getStorageIDs();
        for (int j = 0; j < nodes.length; j++) {
          blockManager.findAndMarkBlockAsCorrupt(blk, nodes[j],
              storageIDs == null ? null: storageIDs[j],
              "client machine reported it");
        }
      }
    } finally {
      ns.writeUnlock();
    }
  }

  /**
   * Start client and service RPC servers.
   */
  void start() {
    clientRpcServer.start();
    if (serviceRpcServer != null) {
      serviceRpcServer.start();
    }
  }

  /**
   * Wait until the RPC servers have shutdown.
   */
  void join() throws InterruptedException {
    clientRpcServer.join();
    if (serviceRpcServer != null) {
      serviceRpcServer.join();
    }
  }

  @Override
  public void commitBlockSynchronization(ExtendedBlock block,
      long newgenerationstamp, long newlength, boolean closeFile,
      boolean deleteblock, DatanodeID[] newtargets, String[] newtargetstorages)
      throws IOException {
    // Not needed for the purpose of object store
    throw new UnsupportedOperationException();
  }

  public static void main(String[] argv) throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    StorageContainerManager scm = new StorageContainerManager(conf);
    scm.start();
    try {
      scm.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}