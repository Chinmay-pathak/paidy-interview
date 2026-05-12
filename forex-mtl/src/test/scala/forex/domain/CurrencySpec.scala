package forex.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CurrencySpec extends AnyWordSpec with Matchers {

  "Currency.fromString" should {
    "parse supported currencies case-insensitively" in {
      Currency.fromString("USD") shouldBe Some(Currency.USD)
      Currency.fromString("usd") shouldBe Some(Currency.USD)
    }

    "return None for unsupported currencies" in {
      Currency.fromString("BTC") shouldBe None
    }
  }

  "Currency.all" should {
    "contain every supported currency exactly once" in {
      Currency.all should contain theSameElementsAs List(
        Currency.AUD,
        Currency.CAD,
        Currency.CHF,
        Currency.EUR,
        Currency.GBP,
        Currency.NZD,
        Currency.JPY,
        Currency.SGD,
        Currency.USD
      )

      Currency.all.distinct.size shouldBe Currency.all.size
    }
  }

}
