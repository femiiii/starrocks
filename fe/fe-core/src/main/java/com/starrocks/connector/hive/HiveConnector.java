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


package com.starrocks.connector.hive;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.common.util.Util;
import com.starrocks.connector.Connector;
import com.starrocks.connector.ConnectorContext;
import com.starrocks.connector.ConnectorMetadata;
import com.starrocks.connector.RemoteFileIO;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.SemanticException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HiveConnector implements Connector {
    public static final String HIVE_METASTORE_URIS = "hive.metastore.uris";
    public static final String HIVE_METASTORE_TYPE = "hive.metastore.type";
    public static final String DUMMY_THRIFT_URI = "thrift://127.0.0.1:9083";
    public static final List<String> SUPPORTED_METASTORE_TYPE = Lists.newArrayList("glue", "dlf");

    private final Map<String, String> properties;
    private final String catalogName;
    private final HiveConnectorInternalMgr internalMgr;
    private final HiveMetadataFactory metadataFactory;

    public HiveConnector(ConnectorContext context) {
        this.properties = context.getProperties();
        this.catalogName = context.getCatalogName();
        this.internalMgr = new HiveConnectorInternalMgr(catalogName, properties);
        this.metadataFactory = createMetadataFactory();
        validate();
        onCreate();
    }

    public void validate() {
        if (properties.containsKey(HIVE_METASTORE_TYPE)) {
            String hiveMetastoreType = properties.get(HIVE_METASTORE_TYPE).toLowerCase();
            if (!SUPPORTED_METASTORE_TYPE.contains(hiveMetastoreType)) {
                throw new SemanticException("hive metastore type [%s] is not supported", hiveMetastoreType);
            }
        }
        String hiveMetastoreUris = Preconditions.checkNotNull(properties.get(HIVE_METASTORE_URIS),
                "%s must be set in properties when creating hive catalog", HIVE_METASTORE_URIS);
        Util.validateMetastoreUris(hiveMetastoreUris);
    }

    @Override
    public ConnectorMetadata getMetadata() {
        return metadataFactory.create();
    }

    private HiveMetadataFactory createMetadataFactory() {
        IHiveMetastore metastore = internalMgr.createHiveMetastore();
        RemoteFileIO remoteFileIO = internalMgr.createRemoteFileIO();

        return new HiveMetadataFactory(
                catalogName,
                metastore,
                remoteFileIO,
                internalMgr.getHiveMetastoreConf(),
                internalMgr.getRemoteFileConf(),
                internalMgr.getPullRemoteFileExecutor(),
                internalMgr.isSearchRecursive(),
                internalMgr.enableHmsEventsIncrementalSync()
        );
    }

    public void onCreate() {
        if (internalMgr.enableHmsEventsIncrementalSync()) {
            Optional<CacheUpdateProcessor> updateProcessor = metadataFactory.getCacheUpdateProcessor();
            updateProcessor.ifPresent(processor -> GlobalStateMgr.getCurrentState().getMetastoreEventsProcessor()
                    .registerCacheUpdateProcessor(catalogName, updateProcessor.get()));
        }
    }

    @Override
    public void shutdown() {
        internalMgr.shutdown();
        metadataFactory.getCacheUpdateProcessor().ifPresent(CacheUpdateProcessor::invalidateAll);
        GlobalStateMgr.getCurrentState().getMetastoreEventsProcessor().unRegisterCacheUpdateProcessor(catalogName);
    }
}
