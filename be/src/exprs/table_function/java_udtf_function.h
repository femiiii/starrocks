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

#include "exprs/table_function/table_function.h"
#include "runtime/runtime_state.h"

namespace starrocks::vectorized {

// Now UDTF only support one column return
class JavaUDTFFunction final : public TableFunction {
public:
    JavaUDTFFunction() = default;
    ~JavaUDTFFunction() override = default;

    Status init(const TFunction& fn, TableFunctionState** state) const override;
    Status prepare(TableFunctionState* state) const override;
    Status open(RuntimeState* runtime_state, TableFunctionState* state) const override;
    std::pair<Columns, ColumnPtr> process(TableFunctionState* state, bool* eos) const override;
    Status close(RuntimeState* _runtime_state, TableFunctionState* state) const override;
};
} // namespace starrocks::vectorized
