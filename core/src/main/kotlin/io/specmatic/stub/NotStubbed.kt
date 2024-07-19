package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.stub.report.StubEndpoint

class NotStubbed(override val response: HttpStubResponse) : StubbedResponseResult {
    override fun log(logs: MutableList<StubEndpoint>, httpRequest: HttpRequest) {

    }
}