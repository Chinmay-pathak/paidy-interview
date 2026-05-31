package forex.http.rates

import forex.domain.Currency
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import org.http4s.dsl.impl.ValidatingQueryParamDecoderMatcher

object QueryParams {

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Currency] =
    QueryParamDecoder[String].emap { value =>
      Currency
        .fromString(value)
        .toRight(ParseFailure("Invalid currency", s"Unsupported currency: $value"))
    }

  object FromQueryParam extends ValidatingQueryParamDecoderMatcher[Currency]("from")
  object ToQueryParam extends ValidatingQueryParamDecoderMatcher[Currency]("to")

}
