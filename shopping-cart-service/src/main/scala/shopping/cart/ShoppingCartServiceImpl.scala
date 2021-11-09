package shopping.cart

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.grpc.GrpcServiceException
import akka.util.Timeout
import io.grpc.Status
import org.slf4j.LoggerFactory
import shopping.cart.proto.AddItemRequest

import java.util.concurrent.TimeoutException
import scala.concurrent.Future

class ShoppingCartServiceImpl(system: ActorSystem[_])
    extends proto.ShoppingCartService {
  import system.executionContext

  private val logger = LoggerFactory.getLogger(getClass)

  implicit private val timeout: Timeout = Timeout.create(
    system.settings.config.getDuration("shopping-cart-service.ask-timeout"))

  private val sharding = ClusterSharding(system)

  override def addItem(in: AddItemRequest): Future[proto.Cart] = {
    logger.info("addItem {} to cart {}", in.itemId, in.cartId)
    val entryRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val reply: Future[ShoppingCart.Summary] =
      entryRef.askWithStatus(ShoppingCart.AddItem(in.itemId, in.quantity, _))
    val response = reply.map(cart => toProtoCart(cart))
    convertError(response)
  }

  private def toProtoCart(cart: ShoppingCart.Summary): proto.Cart =
    proto.Cart(cart.items.iterator.map { case (itemId, quantity) =>
      proto.Item(itemId, quantity)
    }.toSeq)

  def convertError[T](response: Future[T]): Future[T] =
    response.recoverWith {
      case ex: TimeoutException =>
        Future.failed(
          new GrpcServiceException(Status.UNAVAILABLE.withDescription(
            s"Operation timed out: ${ex.getMessage}")))
      case ex =>
        Future.failed(
          new GrpcServiceException(
            Status.INVALID_ARGUMENT.withDescription(ex.getMessage)))
    }
}
