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

#include "aggregate_distinct_streaming_sink_operator.h"

#include <variant>

#include "runtime/current_thread.h"
#include "simd/simd.h"
namespace starrocks::pipeline {

Status AggregateDistinctStreamingSinkOperator::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(Operator::prepare(state));
    RETURN_IF_ERROR(_aggregator->prepare(state, state->obj_pool(), _unique_metrics.get(), _mem_tracker.get()));
    return _aggregator->open(state);
}

void AggregateDistinctStreamingSinkOperator::close(RuntimeState* state) {
    _aggregator->unref(state);
    Operator::close(state);
}

Status AggregateDistinctStreamingSinkOperator::set_finishing(RuntimeState* state) {
    _is_finished = true;

    if (_aggregator->hash_set_variant().size() == 0) {
        _aggregator->set_ht_eos();
    }

    _aggregator->sink_complete();
    return Status::OK();
}

StatusOr<vectorized::ChunkPtr> AggregateDistinctStreamingSinkOperator::pull_chunk(RuntimeState* state) {
    return Status::InternalError("Not support");
}

Status AggregateDistinctStreamingSinkOperator::push_chunk(RuntimeState* state, const vectorized::ChunkPtr& chunk) {
    size_t chunk_size = chunk->num_rows();

    _aggregator->update_num_input_rows(chunk_size);
    COUNTER_SET(_aggregator->input_row_count(), _aggregator->num_input_rows());

    RETURN_IF_ERROR(_aggregator->evaluate_exprs(chunk.get()));

    if (_aggregator->streaming_preaggregation_mode() == TStreamingPreaggregationMode::FORCE_STREAMING) {
        return _push_chunk_by_force_streaming();
    } else if (_aggregator->streaming_preaggregation_mode() == TStreamingPreaggregationMode::FORCE_PREAGGREGATION) {
        return _push_chunk_by_force_preaggregation(chunk->num_rows());
    } else {
        return _push_chunk_by_auto(chunk->num_rows());
    }
}

Status AggregateDistinctStreamingSinkOperator::_push_chunk_by_force_streaming() {
    SCOPED_TIMER(_aggregator->streaming_timer());
    vectorized::ChunkPtr chunk = std::make_shared<vectorized::Chunk>();
    _aggregator->output_chunk_by_streaming(&chunk);
    _aggregator->offer_chunk_to_buffer(chunk);
    return Status::OK();
}

Status AggregateDistinctStreamingSinkOperator::_push_chunk_by_force_preaggregation(const size_t chunk_size) {
    SCOPED_TIMER(_aggregator->agg_compute_timer());

    _aggregator->build_hash_set(chunk_size);

    COUNTER_SET(_aggregator->hash_table_size(), (int64_t)_aggregator->hash_set_variant().size());

    _mem_tracker->set(_aggregator->hash_set_variant().reserved_memory_usage(_aggregator->mem_pool()));
    TRY_CATCH_BAD_ALLOC(_aggregator->try_convert_to_two_level_set());

    return Status::OK();
}

Status AggregateDistinctStreamingSinkOperator::_push_chunk_by_auto(const size_t chunk_size) {
    // TODO: calc the real capacity of hashtable, will add one interface in the class of habletable
    size_t real_capacity = _aggregator->hash_set_variant().capacity() - _aggregator->hash_set_variant().capacity() / 8;
    size_t remain_size = real_capacity - _aggregator->hash_set_variant().size();
    bool ht_needs_expansion = remain_size < chunk_size;
    size_t allocated_bytes = _aggregator->hash_set_variant().allocated_memory_usage(_aggregator->mem_pool());
    if (!ht_needs_expansion ||
        _aggregator->should_expand_preagg_hash_tables(_aggregator->num_input_rows(), chunk_size, allocated_bytes,
                                                      _aggregator->hash_set_variant().size())) {
        // hash table is not full or allow expand the hash table according reduction rate
        SCOPED_TIMER(_aggregator->agg_compute_timer());
        TRY_CATCH_BAD_ALLOC(_aggregator->build_hash_set(chunk_size));
        COUNTER_SET(_aggregator->hash_table_size(), (int64_t)_aggregator->hash_set_variant().size());

        _mem_tracker->set(_aggregator->hash_set_variant().reserved_memory_usage(_aggregator->mem_pool()));
        TRY_CATCH_BAD_ALLOC(_aggregator->try_convert_to_two_level_set());
    } else {
        {
            SCOPED_TIMER(_aggregator->agg_compute_timer());
            TRY_CATCH_BAD_ALLOC(_aggregator->build_hash_set_with_selection(chunk_size));
        }

        {
            SCOPED_TIMER(_aggregator->streaming_timer());
            size_t zero_count = SIMD::count_zero(_aggregator->streaming_selection());
            if (zero_count == 0) {
                vectorized::ChunkPtr chunk = std::make_shared<vectorized::Chunk>();
                _aggregator->output_chunk_by_streaming(&chunk);
                _aggregator->offer_chunk_to_buffer(chunk);
            } else if (zero_count != _aggregator->streaming_selection().size()) {
                vectorized::ChunkPtr chunk = std::make_shared<vectorized::Chunk>();
                _aggregator->output_chunk_by_streaming_with_selection(&chunk);
                _aggregator->offer_chunk_to_buffer(chunk);
            }
        }

        COUNTER_SET(_aggregator->hash_table_size(), (int64_t)_aggregator->hash_set_variant().size());
    }

    return Status::OK();
}
Status AggregateDistinctStreamingSinkOperator::reset_state(RuntimeState* state,
                                                           const std::vector<vectorized::ChunkPtr>& refill_chunks) {
    _is_finished = false;
    return _aggregator->reset_state(state, refill_chunks, this);
}
} // namespace starrocks::pipeline
