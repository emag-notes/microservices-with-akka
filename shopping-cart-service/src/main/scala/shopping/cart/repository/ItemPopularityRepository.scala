package shopping.cart.repository

import scalikejdbc._

trait ItemPopularityRepository {
  def update(session: ScalikeJdbcSession, itemId: String, delta: Int): Unit
  def getItem(session: ScalikeJdbcSession, itemId: String): Option[Long]
}

class ItemPopularityRepositoryImpl() extends ItemPopularityRepository {
  override def update(
      session: ScalikeJdbcSession,
      itemId: String,
      delta: Int): Unit =
    session.db.withinTx { implicit dbSession =>
      // This uses the PostgreSQL `ON CONFLICT` feature.
      // Alternatively, this can be implemented by first issuing the `UPDATE`
      // and checking for the updated rows count. If no rows got updated issue
      // the `INSERT` instead.
      sql"""
           |INSERT INTO item_popularity (item_id, count) VALUES ($itemId, $delta)
           |ON CONFLICT (item_id) DO UPDATE SET count = item_popularity.count + $delta
           |""".stripMargin.executeUpdate().apply()
    }

  override def getItem(
      session: ScalikeJdbcSession,
      itemId: String): Option[Long] =
    if (session.db.isTxAlreadyStarted)
      session.db.withinTx { implicit dbSession =>
        select(itemId)
      }
    else
      session.db.readOnly { implicit dbSession =>
        select(itemId)
      }

  private def select(itemId: String)(
      implicit dBSession: DBSession): Option[Long] =
    sql"SELECT count FROM item_popularity WHERE item_id = $itemId"
      .map(_.long("count"))
      .toOption()
      .apply()
}
