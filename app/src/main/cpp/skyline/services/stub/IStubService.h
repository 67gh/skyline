// SPDX-License-Identifier: MPL-2.0
// Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)

#pragma once

#include <services/serviceman.h>

namespace skyline::service::stub {
    /**
     * @brief A generic stub for services that have not been implemented, it is only created for services listed by the user in "stub_services.txt"
     * @note Every function is unknown to this service, so BaseService::HandleRequest logs it and returns success with an empty response
     */
    class IStubService : public BaseService {
      private:
        std::string serviceName; //!< The name of the stubbed service as requested through sm

      public:
        IStubService(const DeviceState &state, ServiceManager &manager, std::string serviceName) : BaseService(state, manager), serviceName(std::move(serviceName)) {}

        ServiceFunctionDescriptor GetServiceFunction(u32 id, bool isTipc) override {
            Logger::Warn("Stubbed service \"{}\": {} function 0x{:X} was called, returning success without any output", serviceName, isTipc ? "TIPC" : "HIPC", id);
            throw std::out_of_range("Function of a stubbed service");
        }
    };
}
