// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.lake;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import com.staros.util.LockCloseable;
import com.starrocks.common.DdlException;
import com.starrocks.common.UserException;
import com.starrocks.common.util.LeaderDaemon;
import com.starrocks.persist.ShardInfo;
import com.starrocks.proto.DeleteTabletRequest;
import com.starrocks.proto.DeleteTabletResponse;
import com.starrocks.rpc.BrpcProxy;
import com.starrocks.rpc.LakeService;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ShardDeleter extends LeaderDaemon {
    private static final Logger LOG = LogManager.getLogger(ShardDeleter.class);

    @SerializedName(value = "shardIds")
    private final Set<Long> shardIds;

    private final ReentrantReadWriteLock rwLock;

    public ShardDeleter() {
        shardIds = Sets.newHashSet();
        rwLock = new ReentrantReadWriteLock();
    }

    public void addUnusedShardId(Set<Long> tableIds) {
        try (LockCloseable ignored = new LockCloseable(rwLock.writeLock())) {
            shardIds.addAll(tableIds);
        }
    }

    private void deleteUnusedShard() {
        // delete shard and drop lakeTablet
        if (shardIds.isEmpty()) {
            return;
        }

        Map<Long, Set<Long>> shardIdsByBeMap = new HashMap<>();
        // group shards by be
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            for (long shardId : shardIds) {
                try {
                    long backendId = GlobalStateMgr.getCurrentState().getStarOSAgent().getPrimaryBackendIdByShard(shardId);
                    shardIdsByBeMap.computeIfAbsent(backendId, k -> Sets.newHashSet()).add(shardId);
                } catch (UserException ignored1) {
                    // ignore error
                }
            }
        }

        Set<Long> deletedShards = Sets.newHashSet();

        for (Map.Entry<Long, Set<Long>> entry : shardIdsByBeMap.entrySet()) {
            long backendId = entry.getKey();
            Set<Long> shards = entry.getValue();

            // 1. drop tablet
            Backend backend = GlobalStateMgr.getCurrentSystemInfo().getBackend(backendId);
            DeleteTabletRequest request = new DeleteTabletRequest();
            request.tabletIds = Lists.newArrayList(shards);

            try {
                LakeService lakeService = BrpcProxy.getLakeService(backend.getHost(), backend.getBrpcPort());
                DeleteTabletResponse response = lakeService.deleteTablet(request).get();
                if (response != null && response.failedTablets != null && !response.failedTablets.isEmpty()) {
                    LOG.info("failedTablets is {}", response.failedTablets);
                    response.failedTablets.forEach(shards::remove);
                }
            } catch (Throwable e) {
                LOG.error(e);
                continue;
            }

            // 2. delete shard
            try {
                GlobalStateMgr.getCurrentState().getStarOSAgent().deleteShards(shards);
            } catch (DdlException e) {
                LOG.warn("failed to delete shard from starMgr");
                continue;
            }

            deletedShards.addAll(shards);
        }

        // 3. succ both, remove from the map
        try (LockCloseable ignored = new LockCloseable(rwLock.writeLock())) {
            shardIds.removeAll(deletedShards);
        }
        GlobalStateMgr.getCurrentState().getEditLog().logDeleteUnusedShard(deletedShards);
    }

    @Override
    protected void runAfterCatalogReady() {
        deleteUnusedShard();
    }

    public void replayDeleteUnusedShard(ShardInfo shardInfo) {
        try (LockCloseable ignored = new LockCloseable(rwLock.writeLock())) {
            this.shardIds.removeAll(shardInfo.getShardIds());
        }
    }

    public void replayAddUnusedShard(ShardInfo shardInfo) {
        addUnusedShardId(shardInfo.getShardIds());
    }

}