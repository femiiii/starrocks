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

#include "formats/csv/csv_reader.h"

namespace starrocks::vectorized {

Status CSVReader::next_record(Record* record) {
    if (_limit > 0 && _parsed_bytes > _limit) {
        return Status::EndOfFile("Reached limit");
    }
    char* d;
    size_t pos = 0;
    while ((d = _buff.find(_row_delimiter, pos)) == nullptr) {
        pos = _buff.available();
        _buff.compact();
        if (_buff.free_space() == 0) {
            RETURN_IF_ERROR(_expand_buffer());
        }
        RETURN_IF_ERROR(_fill_buffer());
    }
    size_t l = d - _buff.position();
    *record = Record(_buff.position(), l);
    _buff.skip(l + _row_delimiter_length);
    //               ^^ skip record delimiter.
    _parsed_bytes += l + _row_delimiter_length;
    return Status::OK();
}

Status CSVReader::_expand_buffer() {
    if (UNLIKELY(_storage.size() >= kMaxBufferSize)) {
        return Status::InternalError("CSV line length exceed limit " + std::to_string(kMaxBufferSize));
    }
    size_t new_capacity = std::min(_storage.size() * 2, kMaxBufferSize);
    DCHECK_EQ(_storage.data(), _buff.position()) << "should compact buffer before expand";
    _storage.resize(new_capacity);
    CSVBuffer new_buff(_storage.data(), _storage.size());
    new_buff.add_limit(_buff.available());
    DCHECK_EQ(_storage.data(), new_buff.position());
    DCHECK_EQ(_buff.available(), new_buff.available());
    _buff = new_buff;
    return Status::OK();
}

void CSVReader::split_record(const Record& record, Fields* fields) const {
    const char* value = record.data;
    const char* ptr = record.data;
    const size_t size = record.size;

    if (_column_separator_length == 1) {
        for (size_t i = 0; i < size; ++i, ++ptr) {
            if (*ptr == _column_separator[0]) {
                fields->emplace_back(value, ptr - value);
                value = ptr + 1;
            }
        }
    } else {
        const auto* const base = ptr;

        do {
            ptr = static_cast<char*>(
                    memmem(value, size - (value - base), _column_separator.data(), _column_separator_length));
            if (ptr != nullptr) {
                fields->emplace_back(value, ptr - value);
                value = ptr + _column_separator_length;
            }
        } while (ptr != nullptr);

        ptr = record.data + size;
    }
    fields->emplace_back(value, ptr - value);
}

} // namespace starrocks::vectorized
