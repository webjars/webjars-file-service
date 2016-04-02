import javax.inject.{Inject, Singleton}

import play.api.http.HttpFilters
import play.filters.gzip.GzipFilter

@Singleton
class Filters @Inject() (gzipFilter: GzipFilter) extends HttpFilters {
  override val filters = Seq(gzipFilter)
}