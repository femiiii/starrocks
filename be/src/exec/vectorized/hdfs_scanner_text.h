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

#include "exec/vectorized/hdfs_scanner.h"
#include "formats/csv/converter.h"
#include "formats/csv/csv_reader.h"

namespace starrocks::vectorized {

class HdfsTextScanner final : public HdfsScanner {
public:
    HdfsTextScanner() = default;
    ~HdfsTextScanner() override = default;

    Status do_open(RuntimeState* runtime_state) override;
    void do_close(RuntimeState* runtime_state) noexcept override;
    Status do_get_next(RuntimeState* runtime_state, ChunkPtr* chunk) override;
    Status do_init(RuntimeState* runtime_state, const HdfsScannerParams& scanner_params) override;
    Status parse_csv(int chunk_size, ChunkPtr* chunk);

private:
    // create a reader or re init reader
    Status _create_or_reinit_reader();
    Status _get_hive_column_index(const std::string& column_name);

    using ConverterPtr = std::unique_ptr<csv::Converter>;
    std::string _record_delimiter;
    std::string _field_delimiter;
    char _collection_delimiter;
    char _mapkey_delimiter;
    std::vector<Column*> _column_raw_ptrs;
    std::vector<ConverterPtr> _converters;
    std::shared_ptr<CSVReader> _reader = nullptr;
    size_t _current_range_index = 0;
    std::unordered_map<std::string, int> _columns_index;
    bool _no_data = false;
};
} // namespace starrocks::vectorized
