package shopping.cart

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.grpc.GrpcServiceException
import akka.util.Timeout
import io.grpc.Status
import org.slf4j.LoggerFactory
import shopping.cart.proto.{
  AddItemRequest,
  AdjustItemQuantityRequest,
  Cart,
  CheckoutRequest,
  GetCartRequest,
  RemoveItemRequest
}

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
    val response = entryRef
      .askWithStatus(ShoppingCart.AddItem(in.itemId, in.quantity, _))
      .map(toProtoCart)
    convertError(response)
  }

  override def checkout(in: CheckoutRequest): Future[Cart] = {
    logger.info("checkout {}", in.cartId)
    val entryRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val response =
      entryRef.askWithStatus(ShoppingCart.Checkout).map(toProtoCart)
    convertError(response)
  }

  override def getCart(in: GetCartRequest): Future[Cart] = {
    logger.info("getCart {}", in.cartId)
    val entryRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val response = entryRef.ask(ShoppingCart.Get).map { cart =>
      if (cart.items.isEmpty)
        throw new GrpcServiceException(
          Status.NOT_FOUND.withDescription(s"Cart ${in.cartId} not found"))
      else
        toProtoCart(cart)
    }
    convertError(response)
  }

  override def removeItem(in: RemoveItemRequest): Future[Cart] = {
    logger.info("removeItem {} from cart {}", in.itemId, in.cartId)
    val entryRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val response = entryRef
      .askWithStatus(ShoppingCart.RemoveItem(in.itemId, _))
      .map(toProtoCart)
    convertError(response)
  }

  override def adjustItemQuantity(
      in: AdjustItemQuantityRequest): Future[Cart] = {
    logger.info(
      "adjust item {} quantity to {} from {}",
      in.itemId,
      in.quantity,
      in.cartId)
    val entryRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val response = entryRef
      .askWithStatus(ShoppingCart.AdjustItemQuantity(in.itemId, in.quantity, _))
      .map(toProtoCart)
    convertError(response)
  }

  private def toProtoCart(cart: ShoppingCart.Summary): proto.Cart =
    proto.Cart(
      items = cart.items.iterator.map { case (itemId, quantity) =>
        proto.Item(itemId, quantity)
      }.toSeq,
      checkedOut = cart.checkedOut)

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
