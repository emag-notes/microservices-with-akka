package shopping.cart

import org.slf4j.LoggerFactory
import shopping.cart.proto.{ AddItemRequest, Cart }

import scala.concurrent.Future

class ShoppingCartServiceImpl extends proto.ShoppingCartService {
  private val logger = LoggerFactory.getLogger(getClass)

  override def addItem(in: AddItemRequest): Future[Cart] = {
    logger.info("addItem {} to cart {}", in.itemId, in.cartId)
    Future.successful(
      proto.Cart(items =
        List(proto.Item(itemId = in.itemId, quantity = in.quantity))))
  }
}
