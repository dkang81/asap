package asapstack

import spray.json._
import JsonProtocol._
import org.postgresql.util.PGobject
import java.sql.{Array => SqlArray, _}


class KeyValueStore(val collection: String) {
  def apply(bucket: String, key: String): JsValue = {
    DB.execute(conn => read(conn, bucket, key))
  }

  def apply(bucket: String, key: Long): JsValue = {
    apply(bucket, key.toString)
  }

  def apply(bucket: String): Seq[String] = {
    DB.execute(conn => read(conn, bucket))
  }

  def jsonFromPGobject(o: Object) = {
    o.asInstanceOf[PGobject].getValue.asJson
  }

  def read(conn: Connection, bucket: String, key: String): JsValue = {
    readOption(conn, bucket, key).get
  }

  def read(conn: Connection, bucket: String): Seq[String] = {
    val query = "select key from key_value_history kvh where collection = ? and bucket = ? and " +
    "stamp in (select max(stamp) from key_value_history where collection = kvh.collection and bucket = kvh.bucket " +
    "and key = kvh.key) and value::text <> 'null'"
    val statement = conn.prepareStatement(query)
    statement.setString(1, collection)
    statement.setString(2, bucket)
    DB.resultSetToVector(statement.executeQuery()).map(x => x("key").asInstanceOf[String])
  }

  def readOption(conn: Connection, bucket: String, key: String): Option[JsValue] = {
    val query = "select value from key_value_history kvh where collection = ? and bucket = ? and key = ? and " +
    "stamp in (select max(stamp) from key_value_history where collection = kvh.collection and bucket = kvh.bucket " +
    "and key = kvh.key)"
    val statement = conn.prepareStatement(query)
    statement.setString(1, collection)
    statement.setString(2, bucket)
    statement.setString(3, key)
    DB.resultSetToVector(statement.executeQuery()).headOption.map(x => jsonFromPGobject(x("value")))
  }

  def insert(conn: Connection, stamp: Long, bucket: String, key: String, value: String): Unit = {
    val sql = "insert into key_value_history (collection, bucket, key, stamp, value) " +
    "values (?, ?, ?, ?, ?)"
    val valueObj = new PGobject
    valueObj.setType("json")
    valueObj.setValue(value)
    val statement = conn.prepareStatement(sql)
    statement.setString(1, collection)
    statement.setString(2, bucket)
    statement.setString(3, key)
    statement.setLong(4, stamp)
    statement.setObject(5, valueObj)
    statement.executeUpdate()
  }

  def insert(conn: Connection, stamp: Long, bucket: String, key: String, value: JsValue): Unit = {
    insert(conn, stamp, bucket, key, value.compactPrint)
  }

  def insert(conn: Connection, stamp: Long, bucket: String, key: Long, value: JsValue): Unit = {
    insert(conn, stamp, bucket, key.toString, value.compactPrint)
  }

  def insert(conn: Connection, stamp: Long, bucket: String, key: String, value: Long): Unit = {
    insert(conn, stamp, bucket, key, JsNumber(value))
  }

  def update(bucket: String, key: String, value: JsValue): JsValue = {
    update(bucket, key, value.compactPrint)
    value
  }

  def update(bucket: String, key: String, value: String): String = {
    val stamp = getStamp
    DB.executeTransaction(conn => insert(conn, stamp, bucket, key, value))
    value
  }

  def getStamp = {
    DB.executeTransaction {
      conn => {
        val result = DB.runQuery(conn, "select nextval('stamp')");
        val stampValue = result.headOption.map(_("nextval").asInstanceOf[java.lang.Long].longValue).get
        val s = conn.prepareStatement("insert into stamp_time (stamp, universal_time) values (?, ?)")
        s.setLong(1, stampValue)
        s.setTimestamp(2, new java.sql.Timestamp((new java.util.Date).getTime))
        s.executeUpdate()
        stampValue
      }
    }
  }
}

object KeyValueStore {
  def apply(collection: String) = new KeyValueStore(collection)
}
