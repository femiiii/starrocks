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

#include <ctime>
#include <utility>

#include "exec/pipeline/operator.h"
#include "exec/pipeline/pipeline_fwd.h"
#include "exec/pipeline/source_operator.h"
#include "gutil/strings/substitute.h"
namespace starrocks::pipeline {

class Pipeline;
using PipelinePtr = std::shared_ptr<Pipeline>;
using Pipelines = std::vector<PipelinePtr>;
class Pipeline {
public:
    Pipeline() = delete;
    Pipeline(uint32_t id, OpFactories op_factories) : _id(id), _op_factories(std::move(op_factories)) {
        _runtime_profile = std::make_shared<RuntimeProfile>(strings::Substitute("Pipeline (id=$0)", _id));
    }

    uint32_t get_id() const { return _id; }

    OpFactories& get_op_factories() { return _op_factories; }

    void add_op_factory(const OpFactoryPtr& op) { _op_factories.emplace_back(op); }

    Operators create_operators(int32_t degree_of_parallelism, int32_t i) {
        Operators operators;
        for (const auto& factory : _op_factories) {
            operators.emplace_back(factory->create(degree_of_parallelism, i));
        }
        return operators;
    }

    SourceOperatorFactory* source_operator_factory() {
        DCHECK(!_op_factories.empty());
        return down_cast<SourceOperatorFactory*>(_op_factories[0].get());
    }

    RuntimeProfile* runtime_profile() { return _runtime_profile.get(); }

    Status prepare(RuntimeState* state) {
        for (auto& op : _op_factories) {
            RETURN_IF_ERROR(op->prepare(state));
        }
        return Status::OK();
    }

    void close(RuntimeState* state) {
        for (auto& op : _op_factories) {
            op->close(state);
        }
    }

    std::string to_readable_string() const {
        std::stringstream ss;
        ss << "operator-chain: [";
        for (size_t i = 0; i < _op_factories.size(); ++i) {
            if (i == 0) {
                ss << _op_factories[i]->get_name();
            } else {
                ss << " -> " << _op_factories[i]->get_name();
            }
        }
        ss << "]";
        return ss.str();
    }

private:
    uint32_t _id = 0;
    std::shared_ptr<RuntimeProfile> _runtime_profile = nullptr;
    OpFactories _op_factories;
};

} // namespace starrocks::pipeline
