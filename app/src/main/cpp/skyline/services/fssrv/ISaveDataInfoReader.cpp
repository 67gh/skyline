// SPDX-License-Identifier: MPL-2.0

#include "ISaveDataInfoReader.h"

namespace skyline::service::fssrv {
    ISaveDataInfoReader::ISaveDataInfoReader(const DeviceState &state, ServiceManager &manager) : BaseService(state, manager) {}

    Result ISaveDataInfoReader::ReadSaveDataInfo(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        response.Push<u64>(0); // No entries, so the output buffer is left untouched and the guest sees an empty list
        return {};
    }
}
