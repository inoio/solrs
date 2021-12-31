package io.ino.solrs

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers


/**
 * Created by magro on 4/29/14.
 */
class HttpUtilsSpec extends AnyFunSpec with Matchers with FutureAwaits {

  describe("HttpUtils") {

    import HttpUtils._

    it("should return null charset for content-type without charset") {
      getContentCharSet("application/octet-stream") should be (None)
    }

    it("should return charset for content-type with charset") {
      getContentCharSet("application/xml; charset=UTF-8") should be (Some("UTF-8"))
    }

    it("should return mime-type for content-type with charset") {
      getMimeType("application/xml; charset=UTF-8") should be (Some("application/xml"))
    }

  }

}
