package forex.domain

import cats.Show
import java.util.Locale

sealed trait Currency

object Currency {
  case object AUD extends Currency
  case object CAD extends Currency
  case object CHF extends Currency
  case object EUR extends Currency
  case object GBP extends Currency
  case object NZD extends Currency
  case object JPY extends Currency
  case object SGD extends Currency
  case object USD extends Currency

  val all: List[Currency] =
    List(AUD, CAD, CHF, EUR, GBP, NZD, JPY, SGD, USD)

  implicit val show: Show[Currency] = Show.show {
    case AUD => "AUD"
    case CAD => "CAD"
    case CHF => "CHF"
    case EUR => "EUR"
    case GBP => "GBP"
    case NZD => "NZD"
    case JPY => "JPY"
    case SGD => "SGD"
    case USD => "USD"
  }

  def fromString(s: String): Option[Currency] = {
    val normalized = s.trim.toUpperCase(Locale.ROOT)

    all.find(currency => show.show(currency) == normalized)
  }

}
