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

#pragma once

#include "column/vectorized_fwd.h"
#include "common/statusor.h"
#include "exec/pipeline/runtime_filter_types.h"
#include "exprs/vectorized/runtime_filter_bank.h"
#include "gutil/casts.h"
#include "gutil/strings/substitute.h"
#include "runtime/mem_tracker.h"
#include "util/runtime_profile.h"

namespace starrocks {
class Expr;
class ExprContext;
class RuntimeProfile;
class RuntimeState;
using RuntimeFilterProbeCollector = starrocks::vectorized::RuntimeFilterProbeCollector;

namespace pipeline {
class Operator;
class OperatorFactory;
using OperatorPtr = std::shared_ptr<Operator>;
using Operators = std::vector<OperatorPtr>;
using LocalRFWaitingSet = std::set<TPlanNodeId>;

class Operator {
    friend class PipelineDriver;

public:
    Operator(OperatorFactory* factory, int32_t id, std::string name, int32_t plan_node_id, int32_t driver_sequence);
    virtual ~Operator() = default;

    // prepare is used to do the initialization work
    // It's one of the stages of the operator life cycle（prepare -> finishing -> finished -> [cancelled] -> closed)
    // This method will be exactly invoked once in the whole life cycle
    virtual Status prepare(RuntimeState* state);

    // Notifies the operator that no more input chunk will be added.
    // The operator should finish processing.
    // The method should be idempotent, because it may be triggered
    // multiple times in the entire life cycle
    // finish function is used to finish the following operator of the current operator that encounters its EOS
    // and has no data to push into its following operator, but the operator is not finished until its buffered
    // data inside is processed.
    // It's one of the stages of the operator life cycle（prepare -> finishing -> finished -> [cancelled] -> closed)
    // This method will be exactly invoked once in the whole life cycle
    virtual Status set_finishing(RuntimeState* state) { return Status::OK(); }

    // set_finished is used to shutdown both input and output stream of a operator and after its invocation
    // buffered data inside the operator is cleared.
    // This function is used to shutdown preceding operators of the current operator if it is finished in advance,
    // when the query or fragment instance is canceled, set_finished is also called to shutdown unfinished operators.
    // A complex source operator that interacts with the corresponding sink operator in its preceding drivers via
    // an implementation-specific context should override set_finished function, such as LocalExchangeSourceOperator.
    // For an ordinary operator, set_finished function is trivial and just has the same implementation with
    // set_finishing function.
    // It's one of the stages of the operator life cycle（prepare -> finishing -> finished -> [cancelled] -> closed)
    // This method will be exactly invoked once in the whole life cycle
    virtual Status set_finished(RuntimeState* state) { return Status::OK(); }

    // It's one of the stages of the operator life cycle（prepare -> finishing -> finished -> [cancelled] -> closed)
    // - When the fragment exits abnormally, the stage operator will become to CANCELLED between FINISHED and CLOSE.
    // - When the fragment exits normally, there isn't CANCELLED stage for the drivers.
    // Sometimes, the operator need to realize it is cancelled to stop earlier than normal, such as ExchangeSink.
    virtual Status set_cancelled(RuntimeState* state) { return Status::OK(); }

    // when local runtime filters are ready, the operator should bound its corresponding runtime in-filters.
    virtual void set_precondition_ready(RuntimeState* state);

    // close is used to do the cleanup work
    // It's one of the stages of the operator life cycle（prepare -> finishing -> finished -> [cancelled] -> closed)
    // This method will be exactly invoked once in the whole life cycle
    virtual void close(RuntimeState* state);

    // Whether we could pull chunk from this operator
    virtual bool has_output() const = 0;

    // Whether we could push chunk to this operator
    virtual bool need_input() const = 0;

    // Is this operator completely finished processing and no more
    // output chunks will be produced
    virtual bool is_finished() const = 0;

    // pending_finish returns whether this operator still has reference to the object owned by the operator or FragmentContext.
    // It can ONLY be called after calling set_finished().
    // When a driver's sink operator is finished, the driver should wait for pending i/o task completion.
    // Otherwise, pending tasks shall reference to destructed objects in the operator or FragmentContext,
    // since FragmentContext is unregistered prematurely after all the drivers are finalized.
    // Only source and sink operator may return true, and other operators always return false.
    virtual bool pending_finish() const { return false; }

    // Pull chunk from this operator
    // Use shared_ptr, because in some cases (local broadcast exchange),
    // the chunk need to be shared
    virtual StatusOr<vectorized::ChunkPtr> pull_chunk(RuntimeState* state) = 0;

    // Push chunk to this operator
    virtual Status push_chunk(RuntimeState* state, const vectorized::ChunkPtr& chunk) = 0;

    // reset_state is used by MultilaneOperator in cache mechanism, because lanes in MultilaneOperator are
    // re-used by tablets, before the lane serves for the current tablet, it must invoke reset_state to re-prepare
    // the operators (such as: Project, ChunkAccumulate, DictDecode, Aggregate) that is decorated by MultilaneOperator
    // and clear the garbage that previous tablet has produced.
    //
    // In multi-version cache, when cache is hit partially, the partial-hit cache value should be refilled back to the
    // pre-cache operator(e.g. pre-cache Agg operator) that precedes CacheOperator immediately, the Rowsets of delta
    // version and the partial-hit cache value will be merged in this pre-cache operator.
    //
    // which operators should override this functions?
    // 1. operators not decorated by MultiOperator: not required
    // 2. operators decorated by MultilaneOperator and it precedes CacheOperator immediately: required, and must refill back
    // partial-hit cache value via the `chunks` parameter, e.g.
    //  MultilaneOperator<ConjugateOperator<AggregateBlockingSinkOperator, AggregateBlockingSourceOperator>>
    // 3. operators decorated by MultilaneOperator except case 2: e.g. ProjectOperator, Chunk AccumulateOperator and etc.
    virtual Status reset_state(RuntimeState* state, const std::vector<ChunkPtr>& refill_chunks) { return Status::OK(); }

    int32_t get_id() const { return _id; }

    int32_t get_plan_node_id() const { return _plan_node_id; }

    RuntimeProfile* get_runtime_profile() const { return _runtime_profile.get(); }

    virtual std::string get_name() const {
        return strings::Substitute("$0_$1_$2($3)", _name, _plan_node_id, this, is_finished() ? "X" : "O");
    }

    std::string get_raw_name() const { return _name; }

    const LocalRFWaitingSet& rf_waiting_set() const;

    RuntimeFilterHub* runtime_filter_hub();

    std::vector<ExprContext*>& runtime_in_filters();

    RuntimeFilterProbeCollector* runtime_bloom_filters();
    const RuntimeFilterProbeCollector* runtime_bloom_filters() const;

    virtual int64_t global_rf_wait_timeout_ns() const;

    const std::vector<SlotId>& filter_null_value_columns() const;

    // equal to ExecNode::eval_conjuncts(_conjunct_ctxs, chunk), is used to apply in-filters to Operators.
    Status eval_conjuncts_and_in_filters(const std::vector<ExprContext*>& conjuncts, vectorized::Chunk* chunk,
                                         vectorized::FilterPtr* filter = nullptr, bool apply_filter = true);

    // Evaluate conjuncts without cache
    Status eval_conjuncts(const std::vector<ExprContext*>& conjuncts, vectorized::Chunk* chunk,
                          vectorized::FilterPtr* filter = nullptr);

    // equal to ExecNode::eval_join_runtime_filters, is used to apply bloom-filters to Operators.
    void eval_runtime_bloom_filters(vectorized::Chunk* chunk);

    // 1. (-∞, s_pseudo_plan_node_id_upper_bound] is for operator which is not in the query's plan
    // for example, LocalExchangeSinkOperator, LocalExchangeSourceOperator
    // 2. (s_pseudo_plan_node_id_upper_bound, -1] is for operator which is in the query's plan
    // for example, ResultSink
    static const int32_t s_pseudo_plan_node_id_for_memory_scratch_sink;
    static const int32_t s_pseudo_plan_node_id_for_export_sink;
    static const int32_t s_pseudo_plan_node_id_for_olap_table_sink;
    static const int32_t s_pseudo_plan_node_id_for_result_sink;
    static const int32_t s_pseudo_plan_node_id_upper_bound;

    RuntimeProfile* runtime_profile() { return _runtime_profile.get(); }
    RuntimeProfile* common_metrics() { return _common_metrics.get(); }
    RuntimeProfile* unique_metrics() { return _unique_metrics.get(); }

    // The different operators have their own independent logic for calculating Cost
    virtual int64_t get_last_growth_cpu_time_ns() {
        int64_t res = _last_growth_cpu_time_ns;
        _last_growth_cpu_time_ns = 0;
        return res;
    }

    RuntimeState* runtime_state() const;

    void set_prepare_time(int64_t cost_ns);

protected:
    OperatorFactory* _factory;
    const int32_t _id;
    const std::string _name;
    // Which plan node this operator belongs to
    const int32_t _plan_node_id;
    const int32_t _driver_sequence;
    // _common_metrics and _unique_metrics are the only children of _runtime_profile
    // _common_metrics contains the common metrics of Operator, including counters and sub profiles,
    // e.g. OperatorTotalTime/PushChunkNum/PullChunkNum etc.
    // _unique_metrics contains the unique metrics, incluing counters and sub profiles,
    // e.g. ExchangeSinkOperator have some counters to describe the transmission' speed and throughput.
    std::shared_ptr<RuntimeProfile> _runtime_profile;
    std::shared_ptr<RuntimeProfile> _common_metrics;
    std::shared_ptr<RuntimeProfile> _unique_metrics;

    // All the memory usage will be automatically added to the instance level MemTracker by memory allocate hook
    // But for some special operators, we hope to see the memory usage of some special data structures,
    // such as hash table of aggregate operators.
    // So the following indenpendent MemTracker is introduced to record these memory usage
    std::shared_ptr<MemTracker> _mem_tracker = nullptr;
    bool _conjuncts_and_in_filters_is_cached = false;
    std::vector<ExprContext*> _cached_conjuncts_and_in_filters;

    vectorized::RuntimeBloomFilterEvalContext _bloom_filter_eval_context;

    // Common metrics
    RuntimeProfile::Counter* _total_timer = nullptr;
    RuntimeProfile::Counter* _push_timer = nullptr;
    RuntimeProfile::Counter* _pull_timer = nullptr;
    RuntimeProfile::Counter* _finishing_timer = nullptr;
    RuntimeProfile::Counter* _finished_timer = nullptr;
    RuntimeProfile::Counter* _close_timer = nullptr;
    RuntimeProfile::Counter* _prepare_timer = nullptr;

    RuntimeProfile::Counter* _push_chunk_num_counter = nullptr;
    RuntimeProfile::Counter* _push_row_num_counter = nullptr;
    RuntimeProfile::Counter* _pull_chunk_num_counter = nullptr;
    RuntimeProfile::Counter* _pull_row_num_counter = nullptr;
    RuntimeProfile::Counter* _runtime_in_filter_num_counter = nullptr;
    RuntimeProfile::Counter* _runtime_bloom_filter_num_counter = nullptr;
    RuntimeProfile::Counter* _conjuncts_timer = nullptr;
    RuntimeProfile::Counter* _conjuncts_input_counter = nullptr;
    RuntimeProfile::Counter* _conjuncts_output_counter = nullptr;

    // Some extra cpu cost of this operator that not accounted by pipeline driver,
    // such as OlapScanOperator( use separated IO thread to execute the IO task)
    std::atomic_int64_t _last_growth_cpu_time_ns = 0;

private:
    void _init_rf_counters(bool init_bloom);
    void _init_conjuct_counters();
};

class OperatorFactory {
public:
    OperatorFactory(int32_t id, std::string name, int32_t plan_node_id);
    virtual ~OperatorFactory() = default;
    // Create the operator for the specific sequence driver
    // For some operators, when share some status, need to know the degree_of_parallelism
    virtual OperatorPtr create(int32_t degree_of_parallelism, int32_t driver_sequence) = 0;
    virtual bool is_source() const { return false; }
    int32_t id() const { return _id; }
    int32_t plan_node_id() const { return _plan_node_id; }
    virtual Status prepare(RuntimeState* state);
    virtual void close(RuntimeState* state);
    std::string get_name() const { return _name + "_" + std::to_string(_plan_node_id); }
    std::string get_raw_name() const { return _name; }
    // Local rf that take effects on this operator, and operator must delay to schedule to execution on core
    // util the corresponding local rf generated.
    const LocalRFWaitingSet& rf_waiting_set() const { return _rf_waiting_set; }

    // invoked by ExecNode::init_runtime_filter_for_operator to initialize fields involving runtime filter
    void init_runtime_filter(RuntimeFilterHub* runtime_filter_hub, const std::vector<TTupleId>& tuple_ids,
                             const LocalRFWaitingSet& rf_waiting_set, const RowDescriptor& row_desc,
                             const std::shared_ptr<RefCountedRuntimeFilterProbeCollector>& runtime_filter_collector,
                             const std::vector<SlotId>& filter_null_value_columns,
                             const std::vector<TupleSlotMapping>& tuple_slot_mappings) {
        _runtime_filter_hub = runtime_filter_hub;
        _tuple_ids = tuple_ids;
        _rf_waiting_set = rf_waiting_set;
        _row_desc = row_desc;
        _runtime_filter_collector = runtime_filter_collector;
        _filter_null_value_columns = filter_null_value_columns;
        _tuple_slot_mappings = tuple_slot_mappings;
    }
    // when a operator that waiting for local runtime filters' completion is waked, it call prepare_runtime_in_filters
    // to bound its runtime in-filters.
    void prepare_runtime_in_filters(RuntimeState* state) {
        // TODO(satanson): at present, prepare_runtime_in_filters is called in the PipelineDriverPoller thread sequentially,
        //  std::call_once's cost can be ignored, in the future, if mulitple PipelineDriverPollers are employed to dectect
        //  and wake blocked driver, std::call_once is sound but may be blocked.
        std::call_once(_prepare_runtime_in_filters_once, [this, state]() { this->_prepare_runtime_in_filters(state); });
    }

    RuntimeFilterHub* runtime_filter_hub() { return _runtime_filter_hub; }

    std::vector<ExprContext*>& get_runtime_in_filters() { return _runtime_in_filters; }
    RuntimeFilterProbeCollector* get_runtime_bloom_filters() {
        if (_runtime_filter_collector == nullptr) {
            return nullptr;
        }
        return _runtime_filter_collector->get_rf_probe_collector();
    }
    const RuntimeFilterProbeCollector* get_runtime_bloom_filters() const {
        if (_runtime_filter_collector == nullptr) {
            return nullptr;
        }
        return _runtime_filter_collector->get_rf_probe_collector();
    }

    const std::vector<SlotId>& get_filter_null_value_columns() const { return _filter_null_value_columns; }

    void set_runtime_state(RuntimeState* state) { this->_state = state; }

    RuntimeState* runtime_state() const { return _state; }

    RowDescriptor* row_desc() { return &_row_desc; }

    // Whether it has any runtime in-filter or bloom-filter.
    // MUST be invoked after init_runtime_filter.
    bool has_runtime_filters() const;

protected:
    void _prepare_runtime_in_filters(RuntimeState* state);

    const int32_t _id;
    const std::string _name;
    const int32_t _plan_node_id;
    std::shared_ptr<RuntimeProfile> _runtime_profile;
    RuntimeFilterHub* _runtime_filter_hub = nullptr;
    std::vector<TupleId> _tuple_ids;
    // a set of TPlanNodeIds of HashJoinNode who generates Local RF that take effects on this operator.
    LocalRFWaitingSet _rf_waiting_set;
    std::once_flag _prepare_runtime_in_filters_once;
    RowDescriptor _row_desc;
    std::vector<ExprContext*> _runtime_in_filters;
    std::shared_ptr<RefCountedRuntimeFilterProbeCollector> _runtime_filter_collector = nullptr;
    std::vector<SlotId> _filter_null_value_columns;
    // Mappings from input slot to output slot of ancestor exec nodes (include itself).
    // It is used to rewrite runtime in filters.
    std::vector<TupleSlotMapping> _tuple_slot_mappings;

    RuntimeState* _state = nullptr;
};

using OpFactoryPtr = std::shared_ptr<OperatorFactory>;
using OpFactories = std::vector<OpFactoryPtr>;

} // namespace pipeline
} // namespace starrocks
