package com.twitter.cassie

import clocks.{MicrosecondEpochClock, Clock}
import codecs.{Codec, Utf8Codec}
import connection.ClientProvider

import org.apache.cassandra.finagle.thrift
import com.twitter.logging.Logger
import java.nio.ByteBuffer
import java.util.Collections.{singleton => singletonSet}

import java.util.{ArrayList, HashMap, Iterator, List, Map, Set}
import org.apache.cassandra.finagle.thrift
import scala.collection.JavaConversions._ // TODO get rid of this

import com.twitter.util.Future

/**
 * A readable, writable column family with batching capabilities. This is a
 * lightweight object: it inherits a connection pool from the Keyspace.
 *
 * TODO: remove (insert/get)As methods in favor of copying the CF to allow for alternate types.
 */
case class ColumnFamily[Key, Name, Value](
    keyspace: String,
    name: String,
    provider: ClientProvider,
    defaultKeyCodec: Codec[Key],
    defaultNameCodec: Codec[Name],
    defaultValueCodec: Codec[Value],
    readConsistency: ReadConsistency = ReadConsistency.Quorum,
    writeConsistency: WriteConsistency = WriteConsistency.Quorum
    )
    extends ColumnFamilyLike[Key, Name, Value] {

  private[cassie] var clock: Clock = MicrosecondEpochClock
  val log: Logger = Logger.get

  import ColumnFamily._

  def keysAs[K](codec: Codec[K]): ColumnFamily[K, Name, Value] = copy(defaultKeyCodec = codec)
  def namesAs[N](codec: Codec[N]): ColumnFamily[Key, N, Value] = copy(defaultNameCodec = codec)
  def valuesAs[V](codec: Codec[V]): ColumnFamily[Key, Name, V] = copy(defaultValueCodec = codec)
  def consistency(rc: ReadConsistency) = copy(readConsistency = rc)
  def consistency(wc: WriteConsistency) = copy(writeConsistency = wc)

  def newColumn[N, V](n: N, v: V) = Column(n, v)
  def newColumn[N, V](n: N, v: V, ts: Long) = new Column(n, v, Some(ts), None)

  def getColumn(key: Key,
                columnName: Name): Future[Option[Column[Name, Value]]] = {
    getColumns(key, singletonSet(columnName)).map { result => Option(result.get(columnName))}
  }


  def getRow(key: Key): Future[Map[Name, Column[Name, Value]]] = {
    getRowSlice(key, None, None, Int.MaxValue, Order.Normal)
  }

  private[cassie] def getRowSliceAs[K, N, V](key: K,
                             startColumnName: Option[N],
                             endColumnName: Option[N],
                             count: Int,
                             order: Order)
                            (implicit keyCodec: Codec[K], nameCodec: Codec[N], valueCodec: Codec[V]) = {
    val startBytes = startColumnName.map { c => nameCodec.encode(c) }.getOrElse(EMPTY)
    val endBytes = endColumnName.map { c => nameCodec.encode(c) }.getOrElse(EMPTY)
    val pred = new thrift.SlicePredicate()
    pred.setSlice_range(new thrift.SliceRange(startBytes, endBytes, order.reversed, count))
    getSlice(key, pred, keyCodec, nameCodec, valueCodec)
  }

  def getRowSlice(key: Key,
                  startColumnName: Option[Name],
                  endColumnName: Option[Name],
                  count: Int,
                  order: Order): Future[Map[Name, Column[Name, Value]]] = {
    val startBytes = startColumnName.map { c => defaultNameCodec.encode(c) }.getOrElse(EMPTY)
    val endBytes = endColumnName.map { c => defaultNameCodec.encode(c) }.getOrElse(EMPTY)
    val pred = new thrift.SlicePredicate()
    pred.setSlice_range(new thrift.SliceRange(startBytes, endBytes, order.reversed, count))
    getSlice(key, pred, defaultKeyCodec, defaultNameCodec, defaultValueCodec)
  }

  def getColumns(key: Key, columnNames: Set[Name]): Future[Map[Name, Column[Name, Value]]] = {
    val pred = new thrift.SlicePredicate()
    pred.setColumn_names(encodeNames(columnNames))
    getSlice(key, pred, defaultKeyCodec, defaultNameCodec, defaultValueCodec)
  }

  def multigetColumn(keys: Set[Key], columnName: Name): Future[Map[Key, Column[Name, Value]]] = {
    multigetColumns(keys, singletonSet(columnName)).map { rows =>
      val cols: Map[Key, Column[Name, Value]] = new HashMap(rows.size)
      for (rowEntry <- asScalaIterable(rows.entrySet))
        if (!rowEntry.getValue.isEmpty)
          cols.put(rowEntry.getKey, rowEntry.getValue.get(columnName))
      cols
    }
  }

  def multigetColumns(keys: Set[Key], columnNames: Set[Name]) = {
    val cp = new thrift.ColumnParent(name)
    val pred = new thrift.SlicePredicate()
    pred.setColumn_names(encodeNames(columnNames))
    log.debug("multiget_slice(%s, %s, %s, %s, %s)", keyspace, keys, cp, pred, readConsistency.level)
    val encodedKeys = encodeKeys(keys)
    provider.map {
      _.multiget_slice(encodedKeys, cp, pred, readConsistency.level)
    }.map { result =>
      // decode result
      val rows: Map[Key, Map[Name, Column[Name, Value]]] = new HashMap(result.size)
      for (rowEntry <- asScalaIterable(result.entrySet)) {
        val cols: Map[Name, Column[Name, Value]] = new HashMap(rowEntry.getValue.size)
        for (cosc <- asScalaIterable(rowEntry.getValue)) {
          val col = Column.convert(defaultNameCodec, defaultValueCodec, cosc)
          cols.put(col.name, col)
        }
        rows.put(defaultKeyCodec.decode(rowEntry.getKey), cols)
      }
      rows
    }
  }

  def insert(key: Key, column: Column[Name, Value]) = {
    val cp = new thrift.ColumnParent(name)
    val col = Column.convert(defaultNameCodec, defaultValueCodec, clock, column)
    log.debug("insert(%s, %s, %s, %s, %d, %s)", keyspace, key, cp, column.value,
      col.timestamp, writeConsistency.level)
    provider.map {
      _.insert(defaultKeyCodec.encode(key), cp, col, writeConsistency.level)
    }
  }

  def truncate() = provider.map(_.truncate(name))

  def removeColumn(key: Key, columnName: Name) = {
    val cp = new thrift.ColumnPath(name)
    val timestamp = clock.timestamp
    cp.setColumn(defaultNameCodec.encode(columnName))
    log.debug("remove(%s, %s, %s, %d, %s)", keyspace, key, cp, timestamp, writeConsistency.level)
    provider.map { _.remove(defaultKeyCodec.encode(key), cp, timestamp, writeConsistency.level) }
  }

  def removeColumns(key: Key, columnNames: Set[Name]): Future[Void] = {
    batch()
      .removeColumns(key, columnNames)
      .execute()
  }

  def removeColumns(key: Key, columnNames: Set[Name], timestamp: Long): Future[Void] = {
    batch()
      .removeColumns(key, columnNames, timestamp)
      .execute()
  }

  def removeRow(key: Key) = {
    removeRowWithTimestamp(key, clock.timestamp)
  }

  def removeRowWithTimestamp(key: Key, timestamp: Long) = {
    val cp = new thrift.ColumnPath(name)
    log.debug("remove(%s, %s, %s, %d, %s)", keyspace, key, cp, timestamp, writeConsistency.level)
    provider.map { _.remove(defaultKeyCodec.encode(key), cp, timestamp, writeConsistency.level) }
  }

  def batch() = new BatchMutationBuilder(this)

  private[cassie] def batch(mutations: java.util.Map[ByteBuffer, java.util.Map[String, java.util.List[thrift.Mutation]]]) = {
    log.debug("batch_mutate(%s, %s, %s", keyspace, mutations, writeConsistency.level)
    provider.map { _.batch_mutate(mutations, writeConsistency.level) }
  }

  def rowIteratee(batchSize: Int): ColumnIteratee[Key, Name, Value] = {
    val pred = new thrift.SlicePredicate
    pred.setSlice_range(new thrift.SliceRange(EMPTY, EMPTY, false, Int.MaxValue))
    new ColumnIteratee(this, EMPTY, EMPTY, batchSize, pred, defaultKeyCodec, defaultNameCodec, defaultValueCodec)
  }

  def columnIteratee(batchSize: Int,
                     columnName: Name): ColumnIteratee[Key, Name, Value] =
    columnsIteratee(batchSize, singletonSet(columnName))

  def columnsIteratee(batchSize: Int, columnNames: Set[Name]): ColumnIteratee[Key, Name, Value] = {
    val pred = new thrift.SlicePredicate
    pred.setColumn_names(encodeNames(columnNames))
    new ColumnIteratee(this, EMPTY, EMPTY, batchSize, pred, defaultKeyCodec, defaultNameCodec, defaultValueCodec)
  }

  private def getSlice[K, N, V](key: K,
                                pred: thrift.SlicePredicate,
                                keyCodec: Codec[K], nameCodec: Codec[N], valueCodec: Codec[V]): Future[Map[N,Column[N,V]]] = {
    val cp = new thrift.ColumnParent(name)
    log.debug("get_slice(%s, %s, %s, %s, %s)", keyspace, key, cp, pred, readConsistency.level)
    provider.map { _.get_slice(keyCodec.encode(key), cp, pred, readConsistency.level) }
      .map { result =>
        val cols: Map[N,Column[N,V]] = new HashMap(result.size)
        for (cosc <- result.iterator) {
          val col = Column.convert(nameCodec, valueCodec, cosc)
          cols.put(col.name, col)
        }
        cols
      }
  }

  private[cassie] def getRangeSlice(startKey: ByteBuffer,
                                    endKey: ByteBuffer,
                                    count: Int,
                                    predicate: thrift.SlicePredicate) = {
    val cp = new thrift.ColumnParent(name)
    val range = new thrift.KeyRange(count)
    range.setStart_key(startKey)
    range.setEnd_key(endKey)
    log.debug("get_range_slices(%s, %s, %s, %s, %s)", keyspace, cp, predicate, range, readConsistency.level)
    provider.map { _.get_range_slices(cp, predicate, range, readConsistency.level) }
  }

  def encodeSet[V](values: Set[V])(implicit codec: Codec[V]): List[ByteBuffer] = {
    val output = new ArrayList[ByteBuffer](values.size)
    for (value <- asScalaIterable(values))
      output.add(codec.encode(value))
    output
  }

  def encodeNames(values: Set[Name]): List[ByteBuffer] = {
    val output = new ArrayList[ByteBuffer](values.size)
    for (value <- asScalaIterable(values))
      output.add(defaultNameCodec.encode(value))
    output
  }

  def encodeKeys(values: Set[Key]): List[ByteBuffer] = {
    val output = new ArrayList[ByteBuffer](values.size)
    for (value <- asScalaIterable(values))
      output.add(defaultKeyCodec.encode(value))
    output
  }
}

private[cassie] object ColumnFamily
{
  val EMPTY = ByteBuffer.allocate(0)
}
