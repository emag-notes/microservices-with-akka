package shopping.cart

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.javadsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ ExactlyOnceProjection, SourceProvider }
import akka.projection.{ ProjectionBehavior, ProjectionId }
import shopping.cart.repository.{ ItemPopularityRepository, ScalikeJdbcSession }

object ItemPopularityProjection {

  def init(system: ActorSystem[_], repository: ItemPopularityRepository): Unit =
    ShardedDaemonProcess(system).init(
      name = "ItemPopularityProjection",
      numberOfInstances = ShoppingCart.tags.size,
      behaviorFactory = index =>
        ProjectionBehavior(createProjectionFor(system, repository, index)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop))

  private def createProjectionFor(
      system: ActorSystem[_],
      repository: ItemPopularityRepository,
      index: Int)
      : ExactlyOnceProjection[Offset, EventEnvelope[ShoppingCart.Event]] = {
    val tag = ShoppingCart.tags(index)

    val sourceProvider
        : SourceProvider[Offset, EventEnvelope[ShoppingCart.Event]] =
      EventSourcedProvider.eventsByTag[ShoppingCart.Event](
        system = system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = tag)

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId("ItemPopularityProjection", tag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () =>
        new ItemPopularityProjectionHandler(tag, system, repository))(system)
  }
}
