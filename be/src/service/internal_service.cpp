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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/service/internal_service.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "service/internal_service.h"

#include <atomic>
#include <shared_mutex>

#include "brpc/errno.pb.h"
#include "common/closure_guard.h"
#include "common/config.h"
#include "exec/pipeline/fragment_context.h"
#include "exec/pipeline/fragment_executor.h"
#include "gen_cpp/BackendService.h"
#include "gutil/strings/substitute.h"
#include "runtime/buffer_control_block.h"
#include "runtime/data_stream_mgr.h"
#include "runtime/exec_env.h"
#include "runtime/fragment_mgr.h"
#include "runtime/load_channel_mgr.h"
#include "runtime/result_buffer_mgr.h"
#include "runtime/routine_load/routine_load_task_executor.h"
#include "runtime/runtime_filter_worker.h"
#include "service/brpc.h"
#include "util/stopwatch.hpp"
#include "util/thrift_util.h"
#include "util/uid_util.h"

namespace starrocks {

extern std::atomic<bool> k_starrocks_exit;

using PromiseStatus = std::promise<Status>;
using PromiseStatusSharedPtr = std::shared_ptr<PromiseStatus>;

template <typename T>
PInternalServiceImplBase<T>::PInternalServiceImplBase(ExecEnv* exec_env)
        : _exec_env(exec_env),
          _async_thread_pool("async_thread_pool", config::internal_service_async_thread_num,
                             config::internal_service_async_thread_num) {}

template <typename T>
PInternalServiceImplBase<T>::~PInternalServiceImplBase() = default;

template <typename T>
void PInternalServiceImplBase<T>::transmit_data(google::protobuf::RpcController* cntl_base,
                                                const PTransmitDataParams* request, PTransmitDataResult* response,
                                                google::protobuf::Closure* done) {
    VLOG_ROW << "Transmit data: fragment_instance_id = " << print_id(request->finst_id())
             << " node = " << request->node_id();
    auto* cntl = static_cast<brpc::Controller*>(cntl_base);
    if (cntl->request_attachment().size() > 0) {
        PRowBatch* batch = (const_cast<PTransmitDataParams*>(request))->mutable_row_batch();
        butil::IOBuf& io_buf = cntl->request_attachment();
        std::string* tuple_data = batch->mutable_tuple_data();
        io_buf.copy_to(tuple_data);
    }
    // NOTE: we should give a default value to response to avoid concurrent risk.
    // If we don't give response here, stream manager will call done->Run before
    // 'transmit_data()', which will cause a dirty memory access.
    Status st;
    st.to_protobuf(response->mutable_status());
    st = _exec_env->stream_mgr()->transmit_data(request, &done);
    if (!st.ok()) {
        LOG(WARNING) << "transmit_data failed, message=" << st.get_error_msg()
                     << ", fragment_instance_id=" << print_id(request->finst_id()) << ", node=" << request->node_id();
    }
    if (done != nullptr) {
        // NOTE: only when done is not null, we can set response status
        st.to_protobuf(response->mutable_status());
        done->Run();
    }
}

template <typename T>
void PInternalServiceImplBase<T>::transmit_chunk(google::protobuf::RpcController* cntl_base,
                                                 const PTransmitChunkParams* request, PTransmitChunkResult* response,
                                                 google::protobuf::Closure* done) {
    auto task = [=]() { this->_transmit_chunk(cntl_base, request, response, done); };
    if (!_exec_env->query_rpc_pool()->try_offer(std::move(task))) {
        ClosureGuard closure_guard(done);
        Status::ServiceUnavailable("submit transmit_chunk task failed").to_protobuf(response->mutable_status());
    }
}

template <typename T>
void PInternalServiceImplBase<T>::_transmit_chunk(google::protobuf::RpcController* cntl_base,
                                                  const PTransmitChunkParams* request, PTransmitChunkResult* response,
                                                  google::protobuf::Closure* done) {
    auto begin_ts = MonotonicNanos();
    VLOG_ROW << "transmit data: " << (uint64_t)(request) << " fragment_instance_id=" << print_id(request->finst_id())
             << " node=" << request->node_id() << " begin";
    // NOTE: we should give a default value to response to avoid concurrent risk
    // If we don't give response here, stream manager will call done->Run before
    // transmit_data(), which will cause a dirty memory access.
    auto* cntl = static_cast<brpc::Controller*>(cntl_base);
    auto* req = const_cast<PTransmitChunkParams*>(request);
    const auto receive_timestamp = GetCurrentTimeNanos();
    response->set_receive_timestamp(receive_timestamp);
    if (cntl->request_attachment().size() > 0) {
        const butil::IOBuf& io_buf = cntl->request_attachment();
        size_t offset = 0;
        for (size_t i = 0; i < req->chunks().size(); ++i) {
            auto chunk = req->mutable_chunks(i);
            io_buf.copy_to(chunk->mutable_data(), chunk->data_size(), offset);
            offset += chunk->data_size();
        }
    }
    Status st;
    st.to_protobuf(response->mutable_status());
    TRY_CATCH_ALL(st, _exec_env->stream_mgr()->transmit_chunk(*request, &done));
    if (!st.ok()) {
        LOG(WARNING) << "transmit_data failed, message=" << st.get_error_msg()
                     << ", fragment_instance_id=" << print_id(request->finst_id()) << ", node=" << request->node_id();
    }
    if (done != nullptr) {
        // NOTE: only when done is not null, we can set response status
        st.to_protobuf(response->mutable_status());
        done->Run();
    }
    VLOG_ROW << "transmit data: " << (uint64_t)(request) << " fragment_instance_id=" << print_id(request->finst_id())
             << " node=" << request->node_id() << " cost time = " << MonotonicNanos() - begin_ts;
}

template <typename T>
void PInternalServiceImplBase<T>::transmit_runtime_filter(google::protobuf::RpcController* cntl_base,
                                                          const PTransmitRuntimeFilterParams* request,
                                                          PTransmitRuntimeFilterResult* response,
                                                          google::protobuf::Closure* done) {
    auto task = [=]() { this->_transmit_runtime_filter(cntl_base, request, response, done); };
    if (!_exec_env->query_rpc_pool()->try_offer(std::move(task))) {
        ClosureGuard closure_guard(done);
        Status::ServiceUnavailable("submit transmit_runtime_filter task failed")
                .to_protobuf(response->mutable_status());
    }
}
template <typename T>
void PInternalServiceImplBase<T>::_transmit_runtime_filter(google::protobuf::RpcController* cntl_base,
                                                           const PTransmitRuntimeFilterParams* request,
                                                           PTransmitRuntimeFilterResult* response,
                                                           google::protobuf::Closure* done) {
    VLOG_FILE << "transmit runtime filter: fragment_instance_id = " << print_id(request->finst_id())
              << " query_id = " << print_id(request->query_id()) << ", is_partial = " << request->is_partial()
              << ", filter_id = " << request->filter_id() << ", is_pipeline = " << request->is_pipeline();
    ClosureGuard closure_guard(done);
    _exec_env->runtime_filter_worker()->receive_runtime_filter(*request);
    Status st;
    st.to_protobuf(response->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::tablet_writer_open(google::protobuf::RpcController* cntl_base,
                                                     const PTabletWriterOpenRequest* request,
                                                     PTabletWriterOpenResult* response,
                                                     google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    response->mutable_status()->set_status_code(TStatusCode::NOT_IMPLEMENTED_ERROR);
}

template <typename T>
void PInternalServiceImplBase<T>::exec_plan_fragment(google::protobuf::RpcController* cntl_base,
                                                     const PExecPlanFragmentRequest* request,
                                                     PExecPlanFragmentResult* response,
                                                     google::protobuf::Closure* done) {
    auto task = [=]() { this->_exec_plan_fragment(cntl_base, request, response, done); };
    if (!_exec_env->query_rpc_pool()->try_offer(std::move(task))) {
        ClosureGuard closure_guard(done);
        Status::ServiceUnavailable("submit exec_plan_fragment task failed").to_protobuf(response->mutable_status());
    }
}

template <typename T>
void PInternalServiceImplBase<T>::_exec_plan_fragment(google::protobuf::RpcController* cntl_base,
                                                      const PExecPlanFragmentRequest* request,
                                                      PExecPlanFragmentResult* response,
                                                      google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    auto* cntl = static_cast<brpc::Controller*>(cntl_base);
    if (k_starrocks_exit.load(std::memory_order_relaxed)) {
        cntl->SetFailed(brpc::EINTERNAL, "BE is shutting down");
        LOG(WARNING) << "reject exec plan fragment because of exit";
        return;
    }

    auto st = _exec_plan_fragment(cntl);
    if (!st.ok()) {
        LOG(WARNING) << "exec plan fragment failed, errmsg=" << st.get_error_msg();
    }
    st.to_protobuf(response->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::exec_batch_plan_fragments(google::protobuf::RpcController* cntl_base,
                                                            const PExecBatchPlanFragmentsRequest* request,
                                                            PExecBatchPlanFragmentsResult* response,
                                                            google::protobuf::Closure* done) {
    auto task = [=]() { this->_exec_batch_plan_fragments(cntl_base, request, response, done); };
    if (!_exec_env->pipeline_prepare_pool()->try_offer(std::move(task))) {
        ClosureGuard closure_guard(done);
        Status::ServiceUnavailable("submit exec_batch_plan_fragments failed").to_protobuf(response->mutable_status());
    }
}

template <typename T>
void PInternalServiceImplBase<T>::_exec_batch_plan_fragments(google::protobuf::RpcController* cntl_base,
                                                             const PExecBatchPlanFragmentsRequest* request,
                                                             PExecBatchPlanFragmentsResult* response,
                                                             google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    auto* cntl = static_cast<brpc::Controller*>(cntl_base);
    auto ser_request = cntl->request_attachment().to_string();
    std::shared_ptr<TExecBatchPlanFragmentsParams> t_batch_requests = std::make_shared<TExecBatchPlanFragmentsParams>();
    {
        const auto* buf = (const uint8_t*)ser_request.data();
        uint32_t len = ser_request.size();
        if (Status status = deserialize_thrift_msg(buf, &len, TProtocolType::BINARY, t_batch_requests.get());
            !status.ok()) {
            status.to_protobuf(response->mutable_status());
            return;
        }
    }

    auto& common_request = t_batch_requests->common_param;
    auto& unique_requests = t_batch_requests->unique_param_per_instance;

    if (unique_requests.empty()) {
        Status::OK().to_protobuf(response->mutable_status());
        return;
    }

    Status status = _exec_plan_fragment_by_pipeline(common_request, unique_requests[0]);
    status.to_protobuf(response->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::tablet_writer_add_batch(google::protobuf::RpcController* controller,
                                                          const PTabletWriterAddBatchRequest* request,
                                                          PTabletWriterAddBatchResult* response,
                                                          google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    response->mutable_status()->set_status_code(TStatusCode::NOT_IMPLEMENTED_ERROR);
}

template <typename T>
void PInternalServiceImplBase<T>::tablet_writer_add_chunk(google::protobuf::RpcController* cntl_base,
                                                          const PTabletWriterAddChunkRequest* request,
                                                          PTabletWriterAddBatchResult* response,
                                                          google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    response->mutable_status()->set_status_code(TStatusCode::NOT_IMPLEMENTED_ERROR);
}

template <typename T>
void PInternalServiceImplBase<T>::tablet_writer_add_chunks(google::protobuf::RpcController* cntl_base,
                                                           const PTabletWriterAddChunksRequest* request,
                                                           PTabletWriterAddBatchResult* response,
                                                           google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    response->mutable_status()->set_status_code(TStatusCode::NOT_IMPLEMENTED_ERROR);
}

template <typename T>
void PInternalServiceImplBase<T>::tablet_writer_add_segment(google::protobuf::RpcController* controller,
                                                            const PTabletWriterAddSegmentRequest* request,
                                                            PTabletWriterAddSegmentResult* response,
                                                            google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    response->mutable_status()->set_status_code(TStatusCode::NOT_IMPLEMENTED_ERROR);
}

template <typename T>
void PInternalServiceImplBase<T>::tablet_writer_cancel(google::protobuf::RpcController* cntl_base,
                                                       const PTabletWriterCancelRequest* request,
                                                       PTabletWriterCancelResult* response,
                                                       google::protobuf::Closure* done) {}

template <typename T>
Status PInternalServiceImplBase<T>::_exec_plan_fragment(brpc::Controller* cntl) {
    auto ser_request = cntl->request_attachment().to_string();
    TExecPlanFragmentParams t_request;
    {
        const auto* buf = (const uint8_t*)ser_request.data();
        uint32_t len = ser_request.size();
        RETURN_IF_ERROR(deserialize_thrift_msg(buf, &len, TProtocolType::BINARY, &t_request));
    }
    bool is_pipeline = t_request.__isset.is_pipeline && t_request.is_pipeline;
    LOG(INFO) << "exec plan fragment, fragment_instance_id=" << print_id(t_request.params.fragment_instance_id)
              << ", coord=" << t_request.coord << ", backend=" << t_request.backend_num
              << ", is_pipeline=" << is_pipeline << ", chunk_size=" << t_request.query_options.batch_size;
    if (is_pipeline) {
        return _exec_plan_fragment_by_pipeline(t_request, t_request);
    } else {
        return _exec_plan_fragment_by_non_pipeline(t_request);
    }
}

template <typename T>
Status PInternalServiceImplBase<T>::_exec_plan_fragment_by_pipeline(const TExecPlanFragmentParams& t_common_param,
                                                                    const TExecPlanFragmentParams& t_unique_request) {
    pipeline::FragmentExecutor fragment_executor;
    auto status = fragment_executor.prepare(_exec_env, t_common_param, t_unique_request);
    if (status.ok()) {
        return fragment_executor.execute(_exec_env);
    } else {
        return status.is_duplicate_rpc_invocation() ? Status::OK() : status;
    }
}

template <typename T>
Status PInternalServiceImplBase<T>::_exec_plan_fragment_by_non_pipeline(const TExecPlanFragmentParams& t_request) {
    return _exec_env->fragment_mgr()->exec_plan_fragment(t_request);
}

inline std::string cancel_reason_to_string(::starrocks::PPlanFragmentCancelReason reason) {
    switch (reason) {
    case LIMIT_REACH:
        return "LimitReach";
    case USER_CANCEL:
        return "UserCancel";
    case INTERNAL_ERROR:
        return "InternalError";
    case TIMEOUT:
        return "TimeOut";
    default:
        return "UnknownReason";
    }
}

template <typename T>
void PInternalServiceImplBase<T>::cancel_plan_fragment(google::protobuf::RpcController* cntl_base,
                                                       const PCancelPlanFragmentRequest* request,
                                                       PCancelPlanFragmentResult* result,
                                                       google::protobuf::Closure* done) {
    auto task = [=]() { this->_cancel_plan_fragment(cntl_base, request, result, done); };
    if (!_exec_env->query_rpc_pool()->try_offer(std::move(task))) {
        ClosureGuard closure_guard(done);
        Status::ServiceUnavailable("submit cancel_plan_fragment task failed").to_protobuf(result->mutable_status());
    }
}

template <typename T>
void PInternalServiceImplBase<T>::_cancel_plan_fragment(google::protobuf::RpcController* cntl_base,
                                                        const PCancelPlanFragmentRequest* request,
                                                        PCancelPlanFragmentResult* result,
                                                        google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    TUniqueId tid;
    tid.__set_hi(request->finst_id().hi());
    tid.__set_lo(request->finst_id().lo());

    Status st;
    auto reason_string =
            request->has_cancel_reason() ? cancel_reason_to_string(request->cancel_reason()) : "UnknownReason";
    LOG(INFO) << "cancel fragment, fragment_instance_id=" << print_id(tid) << ", reason: " << reason_string;

    if (request->has_is_pipeline() && request->is_pipeline()) {
        TUniqueId query_id;
        if (!request->has_query_id()) {
            LOG(WARNING) << "cancel_plan_fragment must provide query_id in request, upgrade FE";
            st = Status::NotSupported("cancel_plan_fragment must provide query_id in request, upgrade FE");
            st.to_protobuf(result->mutable_status());
            return;
        }
        query_id.__set_hi(request->query_id().hi());
        query_id.__set_lo(request->query_id().lo());
        auto&& query_ctx = _exec_env->query_context_mgr()->get(query_id);
        if (!query_ctx) {
            LOG(INFO) << strings::Substitute("QueryContext already destroyed: query_id=$0, fragment_instance_id=$1",
                                             print_id(query_id), print_id(tid));
            st.to_protobuf(result->mutable_status());
            return;
        }
        auto&& fragment_ctx = query_ctx->fragment_mgr()->get(tid);
        if (!fragment_ctx) {
            LOG(INFO) << strings::Substitute("FragmentContext already destroyed: query_id=$0, fragment_instance_id=$1",
                                             print_id(query_id), print_id(tid));
        } else {
            fragment_ctx->cancel(Status::Cancelled(reason_string));
        }
    } else {
        if (request->has_cancel_reason()) {
            st = _exec_env->fragment_mgr()->cancel(tid, request->cancel_reason());
        } else {
            LOG(INFO) << "cancel fragment, fragment_instance_id=" << print_id(tid);
            st = _exec_env->fragment_mgr()->cancel(tid);
        }
        if (!st.ok()) {
            LOG(WARNING) << "cancel plan fragment failed, errmsg=" << st.get_error_msg();
        }
    }
    st.to_protobuf(result->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::fetch_data(google::protobuf::RpcController* cntl_base,
                                             const PFetchDataRequest* request, PFetchDataResult* result,
                                             google::protobuf::Closure* done) {
    auto task = [=]() { this->_fetch_data(cntl_base, request, result, done); };
    if (!_exec_env->query_rpc_pool()->try_offer(std::move(task))) {
        ClosureGuard closure_guard(done);
        Status::ServiceUnavailable("submit fetch_data task failed").to_protobuf(result->mutable_status());
    }
}

template <typename T>
void PInternalServiceImplBase<T>::_fetch_data(google::protobuf::RpcController* cntl_base,
                                              const PFetchDataRequest* request, PFetchDataResult* result,
                                              google::protobuf::Closure* done) {
    auto* cntl = static_cast<brpc::Controller*>(cntl_base);
    auto* ctx = new GetResultBatchCtx(cntl, result, done);
    _exec_env->result_mgr()->fetch_data(request->finst_id(), ctx);
}

template <typename T>
void PInternalServiceImplBase<T>::trigger_profile_report(google::protobuf::RpcController* controller,
                                                         const PTriggerProfileReportRequest* request,
                                                         PTriggerProfileReportResult* result,
                                                         google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    auto st = _exec_env->fragment_mgr()->trigger_profile_report(request);
    st.to_protobuf(result->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::collect_query_statistics(google::protobuf::RpcController* controller,
                                                           const PCollectQueryStatisticsRequest* request,
                                                           PCollectQueryStatisticsResult* result,
                                                           google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    _exec_env->query_context_mgr()->collect_query_statistics(request, result);
}

template <typename T>
void PInternalServiceImplBase<T>::get_info(google::protobuf::RpcController* controller, const PProxyRequest* request,
                                           PProxyResult* response, google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);

    GenericCountDownLatch<bthread::Mutex, bthread::ConditionVariable> latch(1);

    int timeout_ms =
            request->has_timeout() ? request->timeout() * 1000 : config::routine_load_kafka_timeout_second * 1000;

    // watch estimates the interval before the task is actually executed.
    MonotonicStopWatch watch;
    watch.start();

    if (!_async_thread_pool.try_offer([&]() {
            timeout_ms -= watch.elapsed_time() / 1000 / 1000;
            _get_info_impl(request, response, &latch, timeout_ms);
        })) {
        Status::ServiceUnavailable(
                "too busy to get kafka info, please check the kafka broker status, or set "
                "internal_service_async_thread_num bigger")
                .to_protobuf(response->mutable_status());
        return;
    }

    latch.wait();
}

template <typename T>
void PInternalServiceImplBase<T>::_get_info_impl(
        const PProxyRequest* request, PProxyResult* response,
        GenericCountDownLatch<bthread::Mutex, bthread::ConditionVariable>* latch, int timeout_ms) {
    DeferOp defer([latch] { latch->count_down(); });

    if (timeout_ms <= 0) {
        Status::TimedOut("get kafka info timeout").to_protobuf(response->mutable_status());
        return;
    }

    if (request->has_kafka_meta_request()) {
        std::vector<int32_t> partition_ids;
        Status st = _exec_env->routine_load_task_executor()->get_kafka_partition_meta(request->kafka_meta_request(),
                                                                                      &partition_ids, timeout_ms);
        if (st.ok()) {
            PKafkaMetaProxyResult* kafka_result = response->mutable_kafka_meta_result();
            for (int32_t id : partition_ids) {
                kafka_result->add_partition_ids(id);
            }
        }
        st.to_protobuf(response->mutable_status());
        return;
    }
    if (request->has_kafka_offset_request()) {
        std::vector<int64_t> beginning_offsets;
        std::vector<int64_t> latest_offsets;
        Status st = _exec_env->routine_load_task_executor()->get_kafka_partition_offset(
                request->kafka_offset_request(), &beginning_offsets, &latest_offsets, timeout_ms);
        if (st.ok()) {
            auto result = response->mutable_kafka_offset_result();
            for (int i = 0; i < beginning_offsets.size(); i++) {
                result->add_partition_ids(request->kafka_offset_request().partition_ids(i));
                result->add_beginning_offsets(beginning_offsets[i]);
                result->add_latest_offsets(latest_offsets[i]);
            }
        }
        st.to_protobuf(response->mutable_status());
        return;
    }
    if (request->has_kafka_offset_batch_request()) {
        MonotonicStopWatch watch;
        watch.start();
        for (const auto& offset_req : request->kafka_offset_batch_request().requests()) {
            std::vector<int64_t> beginning_offsets;
            std::vector<int64_t> latest_offsets;

            auto left_ms = timeout_ms - watch.elapsed_time() / 1000 / 1000;
            if (left_ms <= 0) {
                Status::TimedOut("get kafka info timeout").to_protobuf(response->mutable_status());
                return;
            }

            Status st = _exec_env->routine_load_task_executor()->get_kafka_partition_offset(
                    offset_req, &beginning_offsets, &latest_offsets, left_ms);
            auto offset_result = response->mutable_kafka_offset_batch_result()->add_results();
            if (st.ok()) {
                for (int i = 0; i < beginning_offsets.size(); i++) {
                    offset_result->add_partition_ids(offset_req.partition_ids(i));
                    offset_result->add_beginning_offsets(beginning_offsets[i]);
                    offset_result->add_latest_offsets(latest_offsets[i]);
                }
            } else {
                response->clear_kafka_offset_batch_result();
                st.to_protobuf(response->mutable_status());
                return;
            }
        }
    }
    Status::OK().to_protobuf(response->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::get_pulsar_info(google::protobuf::RpcController* controller,
                                                  const PPulsarProxyRequest* request, PPulsarProxyResult* response,
                                                  google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);

    GenericCountDownLatch<bthread::Mutex, bthread::ConditionVariable> latch(1);

    int timeout_ms =
            request->has_timeout() ? request->timeout() * 1000 : config::routine_load_pulsar_timeout_second * 1000;

    // watch estimates the interval before the task is actually executed.
    MonotonicStopWatch watch;
    watch.start();

    if (!_async_thread_pool.try_offer([&]() {
            timeout_ms -= watch.elapsed_time() / 1000 / 1000;
            _get_pulsar_info_impl(request, response, &latch, timeout_ms);
        })) {
        Status::ServiceUnavailable(
                "too busy to get pulsar info, please check the pulsar service status, or set "
                "internal_service_async_thread_num bigger")
                .to_protobuf(response->mutable_status());
        return;
    }

    latch.wait();
}

template <typename T>
void PInternalServiceImplBase<T>::_get_pulsar_info_impl(
        const PPulsarProxyRequest* request, PPulsarProxyResult* response,
        GenericCountDownLatch<bthread::Mutex, bthread::ConditionVariable>* latch, int timeout_ms) {
    DeferOp defer([latch] { latch->count_down(); });

    if (timeout_ms <= 0) {
        Status::TimedOut("get pulsar info timeout").to_protobuf(response->mutable_status());
        return;
    }

    if (request->has_pulsar_meta_request()) {
        std::vector<std::string> partitions;
        Status st = _exec_env->routine_load_task_executor()->get_pulsar_partition_meta(request->pulsar_meta_request(),
                                                                                       &partitions);
        if (st.ok()) {
            PPulsarMetaProxyResult* pulsar_result = response->mutable_pulsar_meta_result();
            for (const std::string& p : partitions) {
                pulsar_result->add_partitions(p);
            }
        }
        st.to_protobuf(response->mutable_status());
        return;
    }
    if (request->has_pulsar_backlog_request()) {
        std::vector<int64_t> backlog_nums;
        Status st = _exec_env->routine_load_task_executor()->get_pulsar_partition_backlog(
                request->pulsar_backlog_request(), &backlog_nums);
        if (st.ok()) {
            auto result = response->mutable_pulsar_backlog_result();
            for (int i = 0; i < backlog_nums.size(); i++) {
                result->add_partitions(request->pulsar_backlog_request().partitions(i));
                result->add_backlog_nums(backlog_nums[i]);
            }
        }
        st.to_protobuf(response->mutable_status());
        return;
    }
    if (request->has_pulsar_backlog_batch_request()) {
        for (const auto& backlog_req : request->pulsar_backlog_batch_request().requests()) {
            std::vector<int64_t> backlog_nums;
            Status st =
                    _exec_env->routine_load_task_executor()->get_pulsar_partition_backlog(backlog_req, &backlog_nums);
            auto backlog_result = response->mutable_pulsar_backlog_batch_result()->add_results();
            if (st.ok()) {
                for (int i = 0; i < backlog_nums.size(); i++) {
                    backlog_result->add_partitions(backlog_req.partitions(i));
                    backlog_result->add_backlog_nums(backlog_nums[i]);
                }
            } else {
                response->clear_pulsar_backlog_batch_result();
                st.to_protobuf(response->mutable_status());
                return;
            }
        }
    }
    Status::OK().to_protobuf(response->mutable_status());
}

template <typename T>
void PInternalServiceImplBase<T>::submit_mv_maintenance_task(google::protobuf::RpcController* controller,
                                                             const PMVMaintenanceTaskRequest* request,
                                                             PMVMaintenanceTaskResult* response,
                                                             google::protobuf::Closure* done) {
    ClosureGuard closure_guard(done);
    auto* cntl = static_cast<brpc::Controller*>(controller);
    cntl->SetFailed(brpc::EINTERNAL, "Not implemented");
    Status st = Status::NotSupported("Not implemented");
    st.to_protobuf(response->mutable_status());
    return;
}

template class PInternalServiceImplBase<PInternalService>;
template class PInternalServiceImplBase<doris::PBackendService>;

} // namespace starrocks
