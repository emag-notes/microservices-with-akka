package shopping.cart

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

object ShoppingCartSpec {
  val config: Config = ConfigFactory
    .parseString("""
      |akka.actor.serialization-bindings {
      | "shopping.cart.CborSerializable" = jackson-cbor
      |}
      |""".stripMargin)
    .withFallback(EventSourcedBehaviorTestKit.config)
}

class ShoppingCartSpec
    extends ScalaTestWithActorTestKit(ShoppingCartSpec.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {
  private val cartId = "testCart"
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[
    ShoppingCart.Command,
    ShoppingCart.Event,
    ShoppingCart.State](system, ShoppingCart(cartId))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "The Shopping Cart" should {
    "add item" in {
      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](
          ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess shouldBe true
      val result2 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 13, _))
      result2.reply.isError shouldBe true
    }

    "checkout" in {
      val result1 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess shouldBe true
      val result2 =
        eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      result2.reply shouldBe StatusReply.Success(
        ShoppingCart.Summary(Map("foo" -> 42), checkedOut = true))
      val result3 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("bar", 13, _))
      result3.reply.isError shouldBe true
    }

    "get" in {
      val result1 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess shouldBe true
      val result2 =
        eventSourcedTestKit.runCommand(ShoppingCart.Get)
      result2.reply shouldBe ShoppingCart.Summary(
        Map("foo" -> 42),
        checkedOut = false)
    }

    "remove item" in {
      val result1 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result2 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("bar", 42, _))
      val result3 =
        eventSourcedTestKit.runCommand(ShoppingCart.RemoveItem("bar", _))
      val result4 =
        eventSourcedTestKit.runCommand(ShoppingCart.Get)
      result4.reply shouldBe ShoppingCart.Summary(
        Map("foo" -> 42),
        checkedOut = false)
    }

    "adjust item quantity" in {
      val result1 =
        eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result2 =
        eventSourcedTestKit.runCommand(
          ShoppingCart.AdjustItemQuantity("foo", 21, _))
      val result3 =
        eventSourcedTestKit.runCommand(ShoppingCart.Get)
      result3.reply shouldBe ShoppingCart.Summary(
        Map("foo" -> 21),
        checkedOut = false)
    }
  }
}
