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
//   https://github.com/apache/incubator-doris/blob/master/be/src/runtime/stream_load/load_stream_mgr.h

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

#pragma once

#include <memory>
#include <mutex>
#include <unordered_map>

#include "runtime/stream_load/stream_load_pipe.h" // for StreamLoadPipe
#include "util/starrocks_metrics.h"
#include "util/uid_util.h" // for std::hash for UniqueId

namespace starrocks {

// used to register all streams in process so that other module can get this stream
class LoadStreamMgr {
public:
    LoadStreamMgr() {
        // Each StreamLoadPipe has a limited buffer size (default 1M), it's not needed to count the
        // actual size of all StreamLoadPipe.
        REGISTER_GAUGE_STARROCKS_METRIC(stream_load_pipe_count, [this]() {
            std::lock_guard<std::mutex> l(_lock);
            return _stream_map.size();
        });
    }
    ~LoadStreamMgr() = default;

    Status put(const UniqueId& id, const std::shared_ptr<StreamLoadPipe>& stream) {
        std::lock_guard<std::mutex> l(_lock);
        auto it = _stream_map.find(id);
        if (it != std::end(_stream_map)) {
            return Status::InternalError("id already exist");
        }
        _stream_map.emplace(id, stream);
        return Status::OK();
    }

    std::shared_ptr<StreamLoadPipe> get(const UniqueId& id) {
        std::lock_guard<std::mutex> l(_lock);
        auto it = _stream_map.find(id);
        if (it == std::end(_stream_map)) {
            return nullptr;
        }
        auto stream = it->second;
        return stream;
    }

    void remove(const UniqueId& id) {
        std::lock_guard<std::mutex> l(_lock);
        auto it = _stream_map.find(id);
        if (it != std::end(_stream_map)) {
            _stream_map.erase(it);
        }
    }

private:
    std::mutex _lock;
    std::unordered_map<UniqueId, std::shared_ptr<StreamLoadPipe>> _stream_map;
};

} // namespace starrocks
