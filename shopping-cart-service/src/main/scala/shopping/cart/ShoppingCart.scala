package shopping.cart

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, SupervisorStrategy }
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityTypeKey
}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  ReplyEffect,
  RetentionCriteria
}

import java.time.Instant
import scala.concurrent.duration.DurationInt

object ShoppingCart {

  /**
   * This interface defines all the commands (messages) that the ShoppingCart actor supports.
   */
  sealed trait Command extends CborSerializable

  /**
   * A command to add an item to the cart.
   *
   * It replies with `StatusReply[Summary]`, which is sent back to the caller when
   * all the events emitted by this command are successfully persisted.
   */
  final case class AddItem(
      itemId: String,
      quantity: Int,
      replyTo: ActorRef[StatusReply[Summary]])
      extends Command

  final case class Checkout(replyTo: ActorRef[StatusReply[Summary]])
      extends Command

  final case class Get(replayTo: ActorRef[Summary]) extends Command

  final case class RemoveItem(
      itemId: String,
      replyTo: ActorRef[StatusReply[Summary]])
      extends Command

  final case class AdjustItemQuantity(
      itemId: String,
      quantity: Int,
      replyTo: ActorRef[StatusReply[Summary]])
      extends Command

  /**
   * Summary of the shopping cart state, used in reply messages.
   */
  final case class Summary(items: Map[String, Int], checkedOut: Boolean)
      extends CborSerializable

  /**
   * This interface defines all the events that the ShoppingCart supports.
   */
  sealed trait Event extends CborSerializable {
    def cartId: String
  }

  final case class ItemAdded(cartId: String, itemId: String, quantity: Int)
      extends Event

  final case class CheckedOut(cartId: String, eventTime: Instant) extends Event

  final case class ItemRemoved(cartId: String, itemId: String) extends Event

  final case class ItemQuantityAdjusted(
      cartId: String,
      itemId: String,
      quantity: Int)
      extends Event

  final case class State(items: Map[String, Int], checkoutDate: Option[Instant])
      extends CborSerializable {
    def isCheckedOut: Boolean = checkoutDate.isDefined
    def checkout(now: Instant): State = copy(checkoutDate = Some(now))
    def toSummary: Summary = Summary(items, isCheckedOut)
    def hasItem(itemId: String): Boolean = items.contains(itemId)
    def isEmpty: Boolean = items.isEmpty
    def updateItem(itemId: String, quantity: Int): State =
      quantity match {
        case 0 => copy(items = items - itemId)
        case _ => copy(items = items + (itemId -> quantity))
      }
    def removeItem(itemId: String): State = copy(items = items - itemId)
    def adjustQuantity(itemId: String, quantity: Int): State =
      copy(items = items.updated(itemId, quantity))
  }
  object State {
    val empty: State = State(items = Map.empty, checkoutDate = None)
  }

  private def handleCommand(
      cartId: String,
      state: State,
      command: Command): ReplyEffect[Event, State] = {
    // The shopping cart behavior changes if it's checked out or not.
    // The commands are handled differently for each case.
    if (state.isCheckedOut)
      checkedOutShoppingCart(state, command)
    else
      openShoppingCart(cartId, state, command)
  }

  private def checkedOutShoppingCart(
      state: State,
      command: Command): ReplyEffect[Event, State] = command match {
    case cmd: AddItem =>
      Effect.reply(cmd.replyTo)(
        StatusReply.Error(
          "Can't add an item to an already checked out shopping cart"))
    case Checkout(replyTo) =>
      Effect.reply(replyTo)(
        StatusReply.Error("Can't checkout already checked out shopping cart"))
    case cmd: RemoveItem =>
      Effect.reply(cmd.replyTo)(
        StatusReply.Error(
          "Can't remove an item to an already checked out shopping cart"))
    case cmd: AdjustItemQuantity =>
      Effect.reply(cmd.replyTo)(StatusReply.Error(
        "Can't adjust a quantity of an item from an already checked out shopping cart"))
    case Get(replayTo) =>
      Effect.reply(replayTo)(state.toSummary)
  }

  private def openShoppingCart(
      cartId: String,
      state: State,
      command: Command): ReplyEffect[Event, State] = command match {
    case AddItem(itemId, quantity, replyTo) =>
      if (state.hasItem(itemId))
        Effect.reply(replyTo)(
          StatusReply.Error(
            s"Item '$itemId' was already added to this shopping cart"))
      else if (quantity <= 0)
        Effect.reply(replyTo)(
          StatusReply.Error("Quantity must be greater than zero"))
      else
        Effect.persist(ItemAdded(cartId, itemId, quantity)).thenReply(replyTo) {
          updatedCart =>
            StatusReply.Success(updatedCart.toSummary)
        }
    case Checkout(replyTo) =>
      if (state.isEmpty)
        Effect.reply(replyTo)(
          StatusReply.Error("Cannot checkout an empty shopping cart"))
      else
        Effect
          .persist(CheckedOut(cartId, Instant.now()))
          .thenReply(replyTo)(updatedCart =>
            StatusReply.Success(updatedCart.toSummary))
    case RemoveItem(itemId, replyTo) =>
      if (!state.hasItem(itemId))
        Effect.reply(replyTo)(
          StatusReply.Error(
            "Cannot remove an item doesn't exist in this shopping cart"))
      else
        Effect
          .persist(ItemRemoved(cartId = cartId, itemId = itemId))
          .thenReply(replyTo) { updatedCart =>
            StatusReply.Success(updatedCart.toSummary)
          }
    case AdjustItemQuantity(itemId, quantity, replyTo) =>
      if (!state.hasItem(itemId))
        Effect.reply(replyTo)(
          StatusReply.Error(
            s"Item '$itemId' doesn't exists in this shopping cart"))
      else if (quantity <= 0)
        Effect.reply(replyTo)(
          StatusReply.Error("Quantity must be greater than zero"))
      else
        Effect
          .persist(
            ItemQuantityAdjusted(
              cartId = cartId,
              itemId = itemId,
              quantity = quantity))
          .thenReply(replyTo) { updatedCart =>
            StatusReply.Success(updatedCart.toSummary)
          }
    case Get(replayTo) =>
      Effect.reply(replayTo)(state.toSummary)
  }

  private def handleEvent(state: State, event: Event): State =
    event match {
      case ItemAdded(_, itemId, quantity) => state.updateItem(itemId, quantity)
      case CheckedOut(_, eventTime)       => state.checkout(eventTime)
      case ItemRemoved(_, itemId)         => state.removeItem(itemId)
      case ItemQuantityAdjusted(_, itemId, quantity) =>
        state.adjustQuantity(itemId, quantity)
    }

  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("ShoppingCart")

  val tags: Seq[String] = Vector.tabulate(5)(i => s"carts-$i")

  def init(system: ActorSystem[_]): Unit = {
    val behaviorFactory: EntityContext[Command] => Behavior[Command] = {
      entityContext =>
        val i = math.abs(entityContext.entityId.hashCode % tags.size)
        val selectedTag = tags(i)
        ShoppingCart(entityContext.entityId, selectedTag)
    }
    ClusterSharding(system).init(Entity(EntityKey)(behaviorFactory))
  }

  def apply(cartId: String, projectionTag: String): Behavior[Command] =
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        persistenceId = PersistenceId(EntityKey.name, cartId),
        emptyState = State.empty,
        commandHandler =
          (state, command) => handleCommand(cartId, state, command),
        eventHandler = (state, event) => handleEvent(state, event))
      .withTagger(_ => Set(projectionTag))
      .withRetention(RetentionCriteria
        .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(
        SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
}
