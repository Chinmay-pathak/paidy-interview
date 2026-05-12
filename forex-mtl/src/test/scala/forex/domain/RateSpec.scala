package forex.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RateSpec extends AnyWordSpec with Matchers {

  "Rate.Pair.all" should {
    "contain every directional pair except same-currency pairs" in {
      Rate.Pair.all should have size 72

      Rate.Pair.all should contain(Rate.Pair(Currency.USD, Currency.JPY))
      Rate.Pair.all should contain(Rate.Pair(Currency.JPY, Currency.USD))
      Rate.Pair.all should not contain (Rate.Pair(Currency.USD, Currency.USD))
    }
  }

}
