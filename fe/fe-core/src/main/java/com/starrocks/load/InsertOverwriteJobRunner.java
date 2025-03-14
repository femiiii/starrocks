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


package com.starrocks.load;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionType;
import com.starrocks.catalog.Table;
import com.starrocks.common.DdlException;
import com.starrocks.persist.InsertOverwriteStateChangeInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryState;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.StatementPlanner;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.PartitionNames;
import com.starrocks.sql.common.DmlException;
import com.starrocks.sql.plan.ExecPlan;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

// InsertOverwriteJobRunner will execute the insert overwrite.
// The main idea is that:
//     1. create temporary partitions as target partitions
//     2. insert selected data into temporary partitions. This is done by modifying the
//          insert target partition names of InsertStmt and replan.
//     3. if insert successfully, swap the temporary partitions with source partitions
//     4. if insert failed, remove the temporary partitions created
//     5. if FE restart, the insert overwrite job will fail.
public class InsertOverwriteJobRunner {
    private static final Logger LOG = LogManager.getLogger(InsertOverwriteJobRunner.class);

    private InsertOverwriteJob job;

    private InsertStmt insertStmt;
    private StmtExecutor stmtExecutor;
    private ConnectContext context;
    private long dbId;
    private long tableId;
    private String postfix;

    private long createPartitionElapse;
    private long insertElapse;

    public InsertOverwriteJobRunner(InsertOverwriteJob job, ConnectContext context, StmtExecutor stmtExecutor) {
        this.job = job;
        this.context = context;
        this.stmtExecutor = stmtExecutor;
        this.insertStmt = job.getInsertStmt();
        this.dbId = job.getTargetDbId();
        this.tableId = job.getTargetTableId();
        this.postfix = "_" + job.getJobId();
        this.createPartitionElapse = 0;
        this.insertElapse = 0;
    }

    // for replay
    public InsertOverwriteJobRunner(InsertOverwriteJob job) {
        this.job = job;
        this.dbId = job.getTargetDbId();
        this.tableId = job.getTargetTableId();
        this.postfix = "_" + job.getJobId();
        this.createPartitionElapse = 0;
        this.insertElapse = 0;
    }

    public boolean isFinished() {
        return job.isFinished();
    }

    // only called from log replay
    // there is no concurrent problem here
    public void cancel() {
        if (isFinished()) {
            LOG.warn("cancel failed. insert overwrite job:{} already finished. state:{}", job.getJobState());
            return;
        }
        try {
            transferTo(InsertOverwriteJobState.OVERWRITE_FAILED);
        } catch (Exception e) {
            LOG.warn("cancel insert overwrite job:{} failed", job.getJobId(), e);
        }
    }

    public void run() throws Exception {
        try {
            handle();
        } catch (Exception e) {
            if (job.getJobState() != InsertOverwriteJobState.OVERWRITE_FAILED) {
                transferTo(InsertOverwriteJobState.OVERWRITE_FAILED);
            }
            throw e;
        }
    }

    public void handle() throws Exception {
        switch (job.getJobState()) {
            case OVERWRITE_PENDING:
                prepare();
                break;
            case OVERWRITE_RUNNING:
                doLoad();
                break;
            case OVERWRITE_FAILED:
                gc(false);
                LOG.warn("insert overwrite job:{} failed. createPartitionElapse:{} ms, insertElapse:{} ms",
                        job.getJobId(), createPartitionElapse, insertElapse);
                break;
            case OVERWRITE_SUCCESS:
                LOG.info("insert overwrite job:{} succeed. createPartitionElapse:{} ms, insertElapse:{} ms",
                        job.getJobId(), createPartitionElapse, insertElapse);
                break;
            default:
                throw new RuntimeException("invalid jobState:" + job.getJobState());
        }
    }

    private void doLoad() throws Exception {
        Preconditions.checkState(job.getJobState() == InsertOverwriteJobState.OVERWRITE_RUNNING);
        createTempPartitions();
        prepareInsert();
        executeInsert();
        doCommit(false);
        transferTo(InsertOverwriteJobState.OVERWRITE_SUCCESS);
    }

    public void replayStateChange(InsertOverwriteStateChangeInfo info) {
        LOG.info("replay state change:{}", info);
        if (job.getJobState() != info.getFromState()) {
            LOG.warn("invalid job info. current state:{}, from state:{}", job.getJobState(), info.getFromState());
            return;
        }
        // state can not be PENDING here
        switch (info.getToState()) {
            case OVERWRITE_RUNNING:
                job.setSourcePartitionIds(info.getSourcePartitionIds());
                job.setTmpPartitionIds(info.getTmpPartitionIds());
                job.setJobState(InsertOverwriteJobState.OVERWRITE_RUNNING);
                break;
            case OVERWRITE_FAILED:
                job.setJobState(InsertOverwriteJobState.OVERWRITE_FAILED);
                LOG.info("replay insert overwrite job:{} to FAILED", job.getJobId());
                gc(true);
                break;
            case OVERWRITE_SUCCESS:
                job.setJobState(InsertOverwriteJobState.OVERWRITE_SUCCESS);
                doCommit(true);
                LOG.info("replay insert overwrite job:{} to SUCCESS", job.getJobId());
                break;
            default:
                LOG.warn("invalid to state:{} for insert overwrite job:{}", info.getToState(), job.getJobId());
                break;
        }
    }

    private void transferTo(InsertOverwriteJobState state) throws Exception {
        if (state == InsertOverwriteJobState.OVERWRITE_SUCCESS) {
            Preconditions.checkState(job.getJobState() == InsertOverwriteJobState.OVERWRITE_RUNNING);
        }
        job.setJobState(state);
        handle();
    }

    private void prepare() throws Exception {
        Preconditions.checkState(job.getJobState() == InsertOverwriteJobState.OVERWRITE_PENDING);
        // get tmpPartitionIds first because they are used to drop created partitions when restart.
        List<Long> tmpPartitionIds = Lists.newArrayList();
        for (int i = 0; i < job.getSourcePartitionIds().size(); ++i) {
            tmpPartitionIds.add(GlobalStateMgr.getCurrentState().getNextId());
        }
        job.setTmpPartitionIds(tmpPartitionIds);
        Database db = getAndWriteLockDatabase(dbId);
        try {
            InsertOverwriteStateChangeInfo info = new InsertOverwriteStateChangeInfo(job.getJobId(), job.getJobState(),
                    InsertOverwriteJobState.OVERWRITE_RUNNING, job.getSourcePartitionIds(), job.getTmpPartitionIds());
            GlobalStateMgr.getCurrentState().getEditLog().logInsertOverwriteStateChange(info);
        } finally {
            db.writeUnlock();
        }

        transferTo(InsertOverwriteJobState.OVERWRITE_RUNNING);
    }

    private void executeInsert() throws Exception {
        long insertStartTimestamp = System.currentTimeMillis();
        // should replan here because prepareInsert has changed the targetPartitionNames of insertStmt
        ExecPlan newPlan = new StatementPlanner().plan(insertStmt, context);
        stmtExecutor.handleDMLStmt(newPlan, insertStmt);
        insertElapse = System.currentTimeMillis() - insertStartTimestamp;
        if (context.getState().getStateType() == QueryState.MysqlStateType.ERR) {
            LOG.warn("insert overwrite failed. error message:{}", context.getState().getErrorMessage());
            throw new DmlException(context.getState().getErrorMessage());
        }
    }

    private void createTempPartitions() throws DdlException {
        long createPartitionStartTimestamp = System.currentTimeMillis();
        Database db = getAndReadLockDatabase(dbId);
        OlapTable targetTable = null;
        try {
            targetTable = checkAndGetTable(db, tableId);
        } finally {
            db.readUnlock();
        }
        PartitionUtils.createAndAddTempPartitionsForTable(db, targetTable, postfix,
                job.getSourcePartitionIds(), job.getTmpPartitionIds());
        createPartitionElapse = System.currentTimeMillis() - createPartitionStartTimestamp;
    }

    private void gc(boolean isReplay) {
        LOG.info("start to garbage collect");
        Database db = getAndWriteLockDatabase(dbId);
        try {
            Table table = db.getTable(tableId);
            if (table == null) {
                throw new DmlException("table:% does not exist in database:%s", tableId, db.getFullName());
            }
            Preconditions.checkState(table instanceof OlapTable);
            OlapTable targetTable = (OlapTable) table;
            if (job.getTmpPartitionIds() != null) {
                for (long pid : job.getTmpPartitionIds()) {
                    LOG.info("drop temp partition:{}", pid);

                    Partition partition = targetTable.getPartition(pid);
                    if (partition != null) {
                        targetTable.dropTempPartition(partition.getName(), true);
                    } else {
                        LOG.warn("partition {} is null", pid);
                    }
                }
            }
            if (!isReplay) {
                InsertOverwriteStateChangeInfo info = new InsertOverwriteStateChangeInfo(job.getJobId(), job.getJobState(),
                        InsertOverwriteJobState.OVERWRITE_FAILED, job.getSourcePartitionIds(), job.getTmpPartitionIds());
                GlobalStateMgr.getCurrentState().getEditLog().logInsertOverwriteStateChange(info);
            }
        } catch (Exception e) {
            LOG.warn("exception when gc insert overwrite job.", e);
        } finally {
            db.writeUnlock();
        }
    }

    private void doCommit(boolean isReplay) {
        Database db = getAndWriteLockDatabase(dbId);
        try {
            OlapTable targetTable = checkAndGetTable(db, tableId);
            List<String> sourcePartitionNames = job.getSourcePartitionIds().stream()
                    .map(partitionId -> targetTable.getPartition(partitionId).getName())
                    .collect(Collectors.toList());
            List<String> tmpPartitionNames = job.getTmpPartitionIds().stream()
                    .map(partitionId -> targetTable.getPartition(partitionId).getName())
                    .collect(Collectors.toList());
            if (targetTable.getPartitionInfo().getType() == PartitionType.RANGE) {
                targetTable.replaceTempPartitions(sourcePartitionNames, tmpPartitionNames, true, false);
            } else {
                targetTable.replacePartition(sourcePartitionNames.get(0), tmpPartitionNames.get(0));
            }
            if (!isReplay) {
                InsertOverwriteStateChangeInfo info = new InsertOverwriteStateChangeInfo(job.getJobId(), job.getJobState(),
                        InsertOverwriteJobState.OVERWRITE_SUCCESS, job.getSourcePartitionIds(), job.getTmpPartitionIds());
                GlobalStateMgr.getCurrentState().getEditLog().logInsertOverwriteStateChange(info);
            }
        } catch (Exception e) {
            LOG.warn("replace partitions failed when insert overwrite into dbId:{}, tableId:{}",
                    job.getTargetDbId(), job.getTargetTableId(), e);
            throw new DmlException("replace partitions failed", e);
        } finally {
            db.writeUnlock();
        }
    }

    private void prepareInsert() {
        Preconditions.checkState(job.getJobState() == InsertOverwriteJobState.OVERWRITE_RUNNING);
        Preconditions.checkState(insertStmt != null);

        Database db = getAndReadLockDatabase(dbId);
        try {
            OlapTable targetTable = checkAndGetTable(db, tableId);
            if (job.getTmpPartitionIds().stream().anyMatch(id -> targetTable.getPartition(id) == null)) {
                throw new DmlException("partitions changed during insert");
            }
            List<String> tmpPartitionNames = job.getTmpPartitionIds().stream()
                    .map(partitionId -> targetTable.getPartition(partitionId).getName())
                    .collect(Collectors.toList());
            PartitionNames partitionNames = new PartitionNames(true, tmpPartitionNames);
            // change the TargetPartitionNames from source partitions to new tmp partitions
            // should replan when load data
            insertStmt.setTargetPartitionNames(partitionNames);
            insertStmt.setTargetPartitionIds(job.getTmpPartitionIds());
        } catch (Exception e) {
            throw new DmlException("prepareInsert exception", e);
        } finally {
            db.readUnlock();
        }
    }

    // when this function return, write lock of db is acquired
    private Database getAndWriteLockDatabase(long dbId) {
        Database db = GlobalStateMgr.getCurrentState().getDb(dbId);
        if (db == null) {
            throw new DmlException("database id:%s does not exist", dbId);
        }
        if (!db.writeLockAndCheckExist()) {
            throw new DmlException("insert overwrite commit failed because locking db:%s failed", dbId);
        }
        return db;
    }

    // when this function return, read lock of db is acquired
    private Database getAndReadLockDatabase(long dbId) {
        Database db = GlobalStateMgr.getCurrentState().getDb(dbId);
        if (db == null) {
            throw new DmlException("database id:%s does not exist", dbId);
        }
        if (!db.readLockAndCheckExist()) {
            throw new DmlException("insert overwrite commit failed because locking db:%s failed", dbId);
        }
        return db;
    }

    private OlapTable checkAndGetTable(Database db, long tableId) {
        Table table = db.getTable(tableId);
        if (table == null) {
            throw new DmlException("table:% does not exist in database:%s", tableId, db.getFullName());
        }
        Preconditions.checkState(table instanceof OlapTable);
        return (OlapTable) table;
    }
}
