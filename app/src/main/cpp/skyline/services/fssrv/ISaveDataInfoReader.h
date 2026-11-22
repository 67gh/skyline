// SPDX-License-Identifier: MPL-2.0

#pragma once

#include <services/serviceman.h>

namespace skyline::service::fssrv {
    /**
     * @brief Reader returned by IFileSystemProxy for enumerating save data entries, it always reports an empty list
     * @url https://switchbrew.org/wiki/Filesystem_services#ISaveDataInfoReader
     */
    class ISaveDataInfoReader : public BaseService {
      public:
        ISaveDataInfoReader(const DeviceState &state, ServiceManager &manager);

        /**
         * @brief Reads the next batch of save data entries into the output buffer and returns how many were written
         * @url https://switchbrew.org/wiki/Filesystem_services#ReadSaveDataInfo
         */
        Result ReadSaveDataInfo(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response);

        SERVICE_DECL(
            SFUNC(0x0, ISaveDataInfoReader, ReadSaveDataInfo)
        )
    };
}
