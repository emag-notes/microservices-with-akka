package shopping.cart

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.SendProducer
import akka.persistence.jdbc.query.javadsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.AtLeastOnceProjection
import akka.projection.{ ProjectionBehavior, ProjectionId }
import org.apache.kafka.common.serialization.{
  ByteArraySerializer,
  StringSerializer
}
import shopping.cart.repository.ScalikeJdbcSession

object PublishEventsProjection {

  def init(system: ActorSystem[_]): Unit = {
    val sendProducer = createProducer(system)
    val topic =
      system.settings.config.getString("shopping-cart-service.kafka.topic")
    ShardedDaemonProcess(system).init(
      name = "PublishEventsProjection",
      numberOfInstances = ShoppingCart.tags.size,
      behaviorFactory = index =>
        ProjectionBehavior(
          createProjectionFor(system, topic, sendProducer, index)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Option(ProjectionBehavior.Stop))
  }

  private def createProducer(
      system: ActorSystem[_]): SendProducer[String, Array[Byte]] = {
    val producerSettings =
      ProducerSettings(system, new StringSerializer, new ByteArraySerializer)
    val sendProducer = SendProducer(producerSettings)(system)
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
      "close-sendProducer") { () =>
      sendProducer.close()
    }
    sendProducer
  }

  private def createProjectionFor(
      system: ActorSystem[_],
      topic: String,
      sendProducer: SendProducer[String, Array[Byte]],
      index: Int)
      : AtLeastOnceProjection[Offset, EventEnvelope[ShoppingCart.Event]] = {
    val tag = ShoppingCart.tags(index)
    val sourceProvider = EventSourcedProvider.eventsByTag[ShoppingCart.Event](
      system = system,
      readJournalPluginId = JdbcReadJournal.Identifier,
      tag = tag)
    JdbcProjection.atLeastOnceAsync(
      projectionId = ProjectionId("PublishEventsProjection", tag),
      sourceProvider = sourceProvider,
      handler =
        () => new PublishEventsProjectionHandler(system, topic, sendProducer),
      sessionFactory = () => new ScalikeJdbcSession())(system)
  }
}
