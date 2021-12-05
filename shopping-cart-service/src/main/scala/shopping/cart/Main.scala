package shopping.cart

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.grpc.GrpcClientSettings
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import org.slf4j.{ Logger, LoggerFactory }
import shopping.cart.repository.{
  ItemPopularityRepositoryImpl,
  ScalikeJdbcSetup
}
import shopping.order.proto.{ ShoppingOrderService, ShoppingOrderServiceClient }

import scala.util.control.NonFatal

object Main {

  val logger: Logger = LoggerFactory.getLogger("shopping.cart.Main")

  def main(args: Array[String]): Unit = {
    val system = ActorSystem[Nothing](Behaviors.empty, "ShoppingCartService")
    try {
      val orderService = orderServiceClient(system)
      init(system, orderService)
    } catch {
      case NonFatal(e) =>
        logger.error("Terminating due to initialization failure.", e)
        system.terminate()
    }
  }

  private def init(
      system: ActorSystem[_],
      orderService: ShoppingOrderService): Unit = {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    ScalikeJdbcSetup.init(system)
    val itemPopularityRepository = new ItemPopularityRepositoryImpl()
    ItemPopularityProjection.init(system, itemPopularityRepository)

    PublishEventsProjection.init(system)
    SendOrderProjection.init(system, orderService)

    val grpcInterface =
      system.settings.config.getString("shopping-cart-service.grpc.interface")
    val grpcPort =
      system.settings.config.getInt("shopping-cart-service.grpc.port")
    ShoppingCart.init(system)
    val grpcService =
      new ShoppingCartServiceImpl(system, itemPopularityRepository)
    ShoppingCartServer.start(grpcInterface, grpcPort, system, grpcService)
  }

  protected def orderServiceClient(
      system: ActorSystem[Nothing]): ShoppingOrderService = {
    val orderServiceClientSettings = GrpcClientSettings
      .connectToServiceAt(
        system.settings.config.getString("shopping-order-service.host"),
        system.settings.config.getInt("shopping-order-service.port"))(system)
      .withTls(false)
    ShoppingOrderServiceClient(orderServiceClientSettings)(system)
  }
}
