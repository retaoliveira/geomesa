/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.arrow.io

import java.io.{ByteArrayOutputStream, Closeable, OutputStream}
import java.nio.channels.Channels
import java.util.PriorityQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

import com.google.common.collect.HashBiMap
import com.typesafe.scalalogging.StrictLogging
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.complex.StructVector
import org.apache.arrow.vector.dictionary.DictionaryProvider.MapDictionaryProvider
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.{ArrowType, DictionaryEncoding}
import org.apache.arrow.vector.util.TransferPair
import org.apache.arrow.vector.{FieldVector, IntVector}
import org.locationtech.geomesa.arrow.ArrowAllocator
import org.locationtech.geomesa.arrow.io.records.{RecordBatchLoader, RecordBatchUnloader}
import org.locationtech.geomesa.arrow.vector.ArrowAttributeReader.ArrowDateReader
import org.locationtech.geomesa.arrow.vector.SimpleFeatureVector.SimpleFeatureEncoding
import org.locationtech.geomesa.arrow.vector._
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.geotools.{SimpleFeatureOrdering, SimpleFeatureTypes}
import org.locationtech.geomesa.utils.index.ByteArrays
import org.locationtech.geomesa.utils.io.CloseWithLogging
import org.locationtech.jts.geom.Geometry
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.annotation.tailrec
import scala.math.Ordering

/**
  * Builds up dictionaries and write record batches. Dictionaries are encoded as deltas
  * to minimize redundant messages.
  *
  * @param sft simple feature type
  * @param dictionaryFields dictionary fields
  * @param encoding simple feature encoding
  * @param sort sort
  * @param initialCapacity initial allocation size, will expand if needed
  */
class DeltaWriter(
    val sft: SimpleFeatureType,
    dictionaryFields: Seq[String],
    encoding: SimpleFeatureEncoding,
    sort: Option[(String, Boolean)],
    initialCapacity: Int
  ) extends Closeable with StrictLogging {

  import DeltaWriter._

  import scala.collection.JavaConversions._

  private val allocator = ArrowAllocator("delta-writer")

  // threading key that we use to group results in the reduce phase
  private var threadingKey: Long = math.abs(ThreadLocalRandom.current().nextLong)
  logger.trace(s"$threadingKey created")

  private val result = new ByteArrayOutputStream

  private val vector = StructVector.empty(sft.getTypeName, allocator)

  private val ordering = sort.map { case (field, reverse) =>
    val o = SimpleFeatureOrdering(sft.indexOf(field))
    if (reverse) { o.reverse } else { o }
  }

  private val idWriter = ArrowAttributeWriter.id(sft, encoding, vector)

  private val writers = sft.getAttributeDescriptors.map { descriptor =>
    val name = descriptor.getLocalName
    val isDictionary = dictionaryFields.contains(name)
    val desc = if (isDictionary) { SimpleFeatureTypes.createDescriptor(s"$name:Integer") } else { descriptor }
    val attribute = ArrowAttributeWriter(sft, desc, None, encoding, vector)

    val dictionary = if (!isDictionary) { None } else {
      val attribute = ArrowAttributeWriter(sft, descriptor, None, encoding, allocator)
      val writer = new BatchWriter(attribute.vector)
      attribute.vector.setInitialCapacity(initialCapacity)
      attribute.vector.allocateNew()
      Some(DictionaryWriter(sft.indexOf(name), attribute, writer, scala.collection.mutable.Map.empty))
    }

    FieldWriter(name, sft.indexOf(name), attribute, dictionary)
  }

  // writer per-dictionary
  private val dictionaryWriters = dictionaryFields.map(f => writers.find(_.name == f).get.dictionary.get)

  // single writer to write out all vectors at once (not including dictionaries)
  private val writer = new BatchWriter(vector)

  // set capacity after all child vectors have been created by the writers, then allocate
  vector.setInitialCapacity(initialCapacity)
  vector.allocateNew()

  /**
    * Clear any existing dictionary values
    */
  def reset(): Unit = {
    val last = threadingKey
    threadingKey = math.abs(ThreadLocalRandom.current().nextLong)
    logger.trace(s"$last resetting to $threadingKey")
    writers.foreach(writer => writer.dictionary.foreach(_.values.clear()))
  }

  /**
    * Writes out a record batch. Format is:
    *
    * 8 bytes long - threading key
    * (foreach dictionaryField) -> {
    *   4 byte int - length of dictionary batch
    *   anyref vector batch with dictionary delta values
    * }
    * 4 byte int - length of record batch
    * record batch (may be dictionary encodings)
    *
    * Note: will sort the feature array in place if sorting is defined
    *
    * @param features features to write
    * @param count number of features to write, starting from array index 0
    * @return serialized record batch
    */
  def encode(features: Array[SimpleFeature], count: Int): Array[Byte] = {

    result.reset()
    result.write(ByteArrays.toBytes(threadingKey))

    ordering.foreach(java.util.Arrays.sort(features, 0, count, _))

    // write out the dictionaries

    dictionaryWriters.foreach { dictionary =>
      // come up with the delta of new dictionary values
      val delta = scala.collection.mutable.SortedSet.empty[AnyRef](dictionaryOrdering)
      var i = 0
      while (i < count) {
        val value = features(i).getAttribute(dictionary.index)
        if (!dictionary.values.contains(value)) {
          delta.add(value)
        }
        i += 1
      }
      val size = dictionary.values.size
      i = 0
      // update the dictionary mappings, and write the new values to the vector
      delta.foreach { n =>
        dictionary.values.put(n, i + size)
        dictionary.attribute.apply(i, n)
        i += 1
      }
      // write out the dictionary batch
      dictionary.attribute.setValueCount(i)
      logger.trace(s"$threadingKey writing dictionary delta with $i values")
      dictionary.writer.writeBatch(i, result)
    }

    // set feature ids in the vector
    if (encoding.fids.isDefined) {
      var i = 0
      while (i < count) {
        idWriter.apply(i, features(i))
        i += 1
      }
      idWriter.setValueCount(count)
    }

    // set attributes in the vector
    writers.foreach { writer =>
      val getAttribute: Int => AnyRef = writer.dictionary match {
        case None =>             i => features(i).getAttribute(writer.index)
        case Some(dictionary) => i => dictionary.values(features(i).getAttribute(writer.index)) // dictionary encoded value
      }
      var i = 0
      while (i < count) {
        writer.attribute.apply(i, getAttribute(i))
        i += 1
      }
      writer.attribute.setValueCount(count)
    }

    logger.trace(s"$threadingKey writing batch with $count values")

    // write out the vector batch
    writer.writeBatch(count, result)

    result.toByteArray
  }

  /**
    * Close the writer
    */
  override def close(): Unit = {
    CloseWithLogging(writer) // also closes `vector`
    dictionaryWriters.foreach(w => CloseWithLogging(w.writer)) // also closes dictionary vectors
    CloseWithLogging(allocator)
  }
}

object DeltaWriter extends StrictLogging {

  import scala.collection.JavaConverters._

  // empty provider
  private val provider = new MapDictionaryProvider()

  private val dictionaryOrdering: Ordering[AnyRef] = new Ordering[AnyRef] {
    override def compare(x: AnyRef, y: AnyRef): Int =
      SimpleFeatureOrdering.nullCompare(x.asInstanceOf[Comparable[Any]], y)
  }

  // note: ordering is flipped as higher values come off the queue first
  private val queueOrdering = new Ordering[(AnyRef, Int, Int)] {
    override def compare(x: (AnyRef, Int, Int), y: (AnyRef, Int, Int)): Int =
      SimpleFeatureOrdering.nullCompare(y._1.asInstanceOf[Comparable[Any]], x._1)
  }

  /**
    * Reduce function for delta records created by DeltaWriter
    *
    * @param sft simple feature type
    * @param dictionaryFields dictionary fields
    * @param encoding simple feature encoding
    * @param sort sort
    * @param batchSize batch size
    * @param deltas output from `DeltaWriter.encode`
    * @return single arrow streaming file, with potentially multiple record batches
    */
  def reduce(
      sft: SimpleFeatureType,
      dictionaryFields: Seq[String],
      encoding: SimpleFeatureEncoding,
      sort: Option[(String, Boolean)],
      batchSize: Int,
      deltas: CloseableIterator[Array[Byte]]): CloseableIterator[Array[Byte]] = {
    new ReducingIterator(sft, dictionaryFields, encoding, sort, batchSize, deltas)
  }

  /**
    * Merge without sorting
    *
    * @param sft simple feature type
    * @param dictionaryFields dictionary fields
    * @param encoding simple feature encoding
    * @param mergedDictionaries merged dictionaries and batch mappings
    * @param batchSize record batch size
    * @param threadedBatches record batches, grouped by threading key
    * @return
    */
  private def reduceNoSort(
      sft: SimpleFeatureType,
      dictionaryFields: Seq[String],
      encoding: SimpleFeatureEncoding,
      mergedDictionaries: MergedDictionaries,
      batchSize: Int,
      threadedBatches: Array[Array[Array[Byte]]]): CloseableIterator[Array[Byte]] = {

    val iter: CloseableIterator[Array[Byte]] = new CloseableIterator[Array[Byte]] {
      private var writeHeader = true
      // JNH: Do we need to synchronize these?!
      private val toLoad = SimpleFeatureVector.create(sft, mergedDictionaries.dictionaries, encoding, batchSize)
      private val result = SimpleFeatureVector.create(sft, mergedDictionaries.dictionaries, encoding, batchSize)
      logger.trace(s"merge unsorted deltas - read schema ${result.underlying.getField}")
      private val loader = new RecordBatchLoader(toLoad.underlying)
      private val unloader = new RecordBatchUnloader(result)

      private val transfers: Seq[(String, (Int, Int, scala.collection.Map[Integer, Integer]) => Unit)] = {
        toLoad.underlying.getChildrenFromFields.asScala.map { fromVector =>
          val name = fromVector.getField.getName
          val toVector = result.underlying.getChild(name)
          val transfer: (Int, Int, scala.collection.Map[Integer, Integer]) => Unit =
            if (fromVector.getField.getDictionary != null) {
              val from = fromVector.asInstanceOf[IntVector]
              val to = toVector.asInstanceOf[IntVector]
              (fromIndex: Int, toIndex: Int, mapping: scala.collection.Map[Integer, Integer]) => {
                val n = from.getObject(fromIndex)
                if (n == null) {
                  to.setNull(toIndex)
                } else {
                  to.setSafe(toIndex, mapping(n))
                }
              }
            } else if (classOf[Geometry].isAssignableFrom(sft.getDescriptor(fromVector.getField.getName).getType.getBinding)) {
//            } else if (SimpleFeatureVector.isGeometryVector(fromVector)) {
              // geometry vectors use FixedSizeList vectors, for which transfer pairs aren't implemented
              val from = GeometryFields.wrap(fromVector).asInstanceOf[GeometryVector[Geometry, FieldVector]]
              val to = GeometryFields.wrap(toVector).asInstanceOf[GeometryVector[Geometry, FieldVector]]
              (fromIndex: Int, toIndex: Int, _: scala.collection.Map[Integer, Integer]) => {
                from.transfer(fromIndex, toIndex, to)
              }
            } else {
              val pair = fromVector.makeTransferPair(toVector)
              (fromIndex: Int, toIndex: Int, _: scala.collection.Map[Integer, Integer]) => {
                pair.copyValueSafe(fromIndex, toIndex)
              }
            }
          (name, transfer)
        }
      }

      private val threadIterator = threadedBatches.iterator
      private var threadIndex = -1
      private var batches: Iterator[Array[Byte]] = Iterator.empty
      private var mappings: Map[String, scala.collection.Map[Integer, Integer]] = _
      private var count = 0 // records read in current batch

      override def hasNext: Boolean = count < toLoad.reader.getValueCount || loadNextBatch()

      override def next(): Array[Byte] = {
        var total = 0
        while (total < batchSize && hasNext) {
          // read the rest of the current vector, up to the batch size
          val toRead = math.min(batchSize - total, toLoad.reader.getValueCount - count)
          transfers.foreach { case (name, transfer) =>
            val mapping = mappings.get(name).orNull
            logger.trace(s"dictionary mappings for $name: $mapping")
            var i = 0
            while (i < toRead) {
              transfer(i + count, i + total, mapping)
              i += 1
            }
          }
          count += toRead
          total += toRead
        }

        if (writeHeader) {
          // write the header in the first result, which includes the metadata and dictionaries
          writeHeader = false
          writeHeaderAndFirstBatch(result, mergedDictionaries.dictionaries, None, total)
        } else {
          unloader.unload(total)
        }
      }

      override def close(): Unit = CloseWithLogging.raise(Seq(toLoad, result, mergedDictionaries))

      /**
        * Read the next batch
        *
        * @return true if there was a batch to load, false if we've read all batches
        */
      @tailrec
      private def loadNextBatch(): Boolean = {
        if (batches.hasNext) {
          val batch = batches.next
          // skip the dictionary batches
          var offset = 8 // initial threading key offset
          dictionaryFields.foreach { _ =>
            offset += ByteArrays.readInt(batch, offset) + 4
          }
          val messageLength = ByteArrays.readInt(batch, offset)
          offset += 4 // skip over message length bytes
          // load the record batch
          loader.load(batch, offset, messageLength)
          if (toLoad.reader.getValueCount > 0) {
            count = 0 // reset count for this batch
            true
          } else {
            loadNextBatch()
          }
        } else if (threadIterator.hasNext) {
          threadIndex += 1
          // set the mappings for this thread
          mappings = mergedDictionaries.mappings.map { case (f, m) => (f, m(threadIndex)) }
          batches = threadIterator.next.iterator
          loadNextBatch()
        } else {
          false
        }
      }
    }

    createFileFromBatches(sft, mergedDictionaries.dictionaries, encoding, None, iter, firstBatchHasHeader = true)
  }

  /**
    * Merge with sorting. Each batch is assumed to be already sorted
    *
    * @param sft simple feature type
    * @param dictionaryFields dictionary fields
    * @param encoding simple feature encoding
    * @param mergedDictionaries merged dictionaries and batch mappings
    * @param sortBy sort field
    * @param reverse reverse sort or not
    * @param batchSize record batch size
    * @param threadedBatches record batches, grouped by threading key, internally sorted
    * @return
    */
  private def reduceWithSort(
      sft: SimpleFeatureType,
      dictionaryFields: Seq[String],
      encoding: SimpleFeatureEncoding,
      mergedDictionaries: MergedDictionaries,
      sortBy: String,
      reverse: Boolean,
      batchSize: Int,
      threadedBatches: Array[Array[Array[Byte]]]): CloseableIterator[Array[Byte]] = {

    import org.locationtech.geomesa.utils.conversions.ScalaImplicits.RichArray

    val dictionaries = mergedDictionaries.dictionaries
    val sortIsDate = sft.getDescriptor(sortBy).getType.getBinding == classOf[java.util.Date]

    // gets the attribute we're sorting by from the i-th feature in the vector
    val getSortAttribute: (ArrowAttributeReader, scala.collection.Map[Integer, Integer], Int) => AnyRef = {
      if (dictionaries.contains(sortBy)) {
        // since we've sorted the dictionaries, we can just compare the encoded index values
        (reader, mappings, i) => mappings(reader.asInstanceOf[ArrowDictionaryReader].getEncoded(i))
      } else {
        (reader, _, i) =>
//            reader.apply(i)
          if (sortIsDate) {
            reader.asInstanceOf[ArrowDateReader].getTime(i).asInstanceOf[AnyRef]
          } else {
            reader.apply(i)
          }
      }
    }

    // JNH: This one is silly, right?
    val result: SimpleFeatureVector = synchronized {
      SimpleFeatureVector.create(sft, dictionaries, encoding)
    }
    val unloader = new RecordBatchUnloader(result)

    logger.trace(s"merging sorted deltas - read schema: ${result.underlying.getField}")

    // builder for our merge array - (vector, reader for sort values, transfers to result, dictionary mappings)
    val mergeBuilder = Array.newBuilder[(SimpleFeatureVector, ArrowAttributeReader, Seq[(Int, Int) => Unit], scala.collection.Map[Integer, Integer])]
    mergeBuilder.sizeHint(threadedBatches.foldLeft(0)((sum, a) => sum + a.length))

    threadedBatches.foreachIndex { case (batches, batchIndex) =>
      val mappings = mergedDictionaries.mappings.map { case (f, m) => (f, m(batchIndex)) }
      logger.trace(s"loading ${batches.length} batch(es) from a single thread")

      batches.foreach { batch =>
        val toLoad = SimpleFeatureVector.create(sft, dictionaries, encoding)
        val loader = new RecordBatchLoader(toLoad.underlying)

        // skip the dictionary batches
        var offset = 8
        dictionaryFields.foreach { _ =>
          offset += ByteArrays.readInt(batch, offset) + 4
        }
        val messageLength = ByteArrays.readInt(batch, offset)
        offset += 4 // skip the length bytes
        // load the record batch
        loader.load(batch, offset, messageLength)
        val transfers: Seq[(Int, Int) => Unit] = toLoad.underlying.getChildrenFromFields.asScala.map { fromVector =>
          // JNH: Buckshot sync'ing.
          fromVector.synchronized {
            val toVector = result.synchronized {
              result.underlying.getChild(fromVector.getField.getName)
            }
            if (fromVector.getField.getDictionary != null) {
              val mapping = mappings(fromVector.getField.getName)
              val to = toVector.asInstanceOf[IntVector]
              (fromIndex: Int, toIndex: Int) => {
                val n = fromVector.getObject(fromIndex).asInstanceOf[Integer]
                if (n == null) {
                  to.setNull(toIndex)
                } else {
                  to.setSafe(toIndex, mapping(n))
                }
              }
            } else if (classOf[Geometry].isAssignableFrom(sft.getDescriptor(fromVector.getField.getName).getType.getBinding)) {
              //          } else if (SimpleFeatureVector.isGeometryVector(fromVector)) {
              // geometry vectors use FixedSizeList vectors, for which transfer pairs aren't implemented
              val from = GeometryFields.wrap(fromVector).asInstanceOf[GeometryVector[Geometry, FieldVector]]
              val to = GeometryFields.wrap(toVector).asInstanceOf[GeometryVector[Geometry, FieldVector]]
              (fromIndex: Int, toIndex: Int) => {
                from.transfer(fromIndex, toIndex, to)
              }
            } else {
              val transfer = fromVector.makeTransferPair(toVector)
              (fromIndex: Int, toIndex: Int) => transfer.copyValueSafe(fromIndex, toIndex)
            }
          }
        }
        val mapVector = toLoad.underlying
        val dict = dictionaries.get(sortBy)
        val sortReader = ArrowAttributeReader(sft.getDescriptor(sortBy), mapVector.getChild(sortBy), dict, encoding)
        mergeBuilder += ((toLoad, sortReader, transfers, mappings.get(sortBy).orNull))
      }
    }

    val toMerge: Array[(SimpleFeatureVector, ArrowAttributeReader, Seq[(Int, Int) => Unit], collection.Map[Integer, Integer])] = mergeBuilder.result()

    // we do a merge sort of each batch
    // sorted queue of [(current batch value, number of the batch, current index in that batch)]
    val queue: PriorityQueue[(AnyRef, Int, Int)] = {
      val o: Ordering[(AnyRef, Int, Int)] = if (reverse) { queueOrdering.reverse } else { queueOrdering }
      new PriorityQueue[(AnyRef, Int, Int)](o)
      //scala.collection.mutable.PriorityQueue.empty[(AnyRef, Int, Int)](o)
    }

    toMerge.foreachIndex { case ((vector, sort, _, mappings), i) =>
      if (vector.reader.getValueCount > 0) {
        queue.add((getSortAttribute(sort, mappings, 0), i, 0))
      }
    }

    var writtenHeader = false

    // gets the next record batch to write - returns null if no further records
    def nextBatch(): Array[Byte] = {
      if (queue.isEmpty) { null } else {
        result.synchronized {
          result.clear()
        }
        var resultIndex = 0
        // copy the next sorted value and then queue and sort the next element out of the batch we copied from
        do {
          val (_, batch, i) = queue.remove()
          val (vector, sort, transfers, mappings) = toMerge(batch)
          transfers.foreach(_.apply(i, resultIndex))
          result.synchronized {
            result.underlying.setIndexDefined(resultIndex)
          }
          resultIndex += 1
          val nextBatchIndex = i + 1
          if (vector.reader.getValueCount > nextBatchIndex) {
            val value = getSortAttribute(sort, mappings, nextBatchIndex)
            queue.add((value, batch, nextBatchIndex))
          }
        } while (!queue.isEmpty && resultIndex < batchSize)

        if (writtenHeader) {
          result.synchronized {
            unloader.unload(resultIndex)
          }
        } else {
          // write the header in the first result, which includes the metadata and dictionaries
          writtenHeader = true
          /// JNH: Do we need to synchronize this?  Gonna sync' em all!
          result.synchronized {
            writeHeaderAndFirstBatch(result, dictionaries, Some(sortBy -> reverse), resultIndex)
          }
        }
      }
    }

    val merged: CloseableIterator[Array[Byte]] = new CloseableIterator[Array[Byte]] {
      private var batch: Array[Byte] = _

      override def hasNext: Boolean = {
        if (batch == null) {
          batch = nextBatch()
        }
        batch != null
      }

      override def next(): Array[Byte] = {
        val res = batch
        batch = null
        res
      }

      // JNH: Let's try and make sure that we don't get two threads calling close at the same instant.
      override def close(): Unit = this.synchronized {
        CloseWithLogging(result)
        toMerge.foreach { case (vector, _,  _, _) => CloseWithLogging(vector) }
        CloseWithLogging(mergedDictionaries)
      }
    }

    createFileFromBatches(sft, dictionaries, encoding, Some(sortBy -> reverse), merged, firstBatchHasHeader = true)
  }

  /**
    * Merge delta dictionary batches
    *
    * @param sft simple feature type
    * @param dictionaryFields dictionary fields
    * @param deltas Seq of threaded dictionary deltas
    * @return
    */
  private def mergeDictionaries(
      sft: SimpleFeatureType,
      dictionaryFields: Seq[String],
      deltas: Array[Array[Array[Byte]]],
      encoding: SimpleFeatureEncoding): MergedDictionaries = {
    import org.locationtech.geomesa.utils.conversions.ScalaImplicits.{RichArray, RichTraversableOnce}

    val allocator = ArrowAllocator("merge-dictionaries")

    if (dictionaryFields.isEmpty) {
      return MergedDictionaries(Map.empty, Map.empty, allocator)
    }

    // create a vector for each dictionary field
    def createNewVectors: Array[ArrowAttributeReader] = {
      val builder = Array.newBuilder[ArrowAttributeReader]
      builder.sizeHint(dictionaryFields.length)
      dictionaryFields.foreach { f =>
        val descriptor = sft.getDescriptor(f)
        // use the writer to create the appropriate child vector
        val vector = ArrowAttributeWriter(sft, descriptor, None, encoding, allocator).vector
        builder += ArrowAttributeReader(descriptor, vector, None, encoding)
      }
      builder.result
    }

    // final results
    val results = createNewVectors

    // re-used queue, gets emptied after each dictionary field
    // [(dictionary value, batch index, index of value in the batch)]
    val queue = scala.collection.mutable.PriorityQueue.empty[(AnyRef, Int, Int)](queueOrdering)

    // merge each threaded delta vector into a single dictionary for that thread
    // Array[(dictionary vector, transfers to result, batch delta mappings)]
    val allMerges: Array[(Array[ArrowAttributeReader], Array[TransferPair], Array[HashBiMap[Integer, Integer]])] = deltas.map { deltas =>
      // deltas are threaded batches containing partial dictionary vectors

      // per-dictionary vectors for our final merged results for this threaded batch
      val dictionaries = createNewVectors

      // the delta vectors, each sorted internally
      val toMerge: Array[(Array[ArrowAttributeReader], Array[TransferPair])] = deltas.map { bytes =>
        val vectors = createNewVectors // per-dictionary vectors from this batch

        var i = 0
        var offset = 8 // start after threading key
        while (i < dictionaries.length) {
          val length = ByteArrays.readInt(bytes, offset)
          offset += 4 // increment past length
          if (length > 0) {
            new RecordBatchLoader(vectors(i).vector).load(bytes, offset, length)
            offset += length
          }
          i += 1
        }
        logger.trace(s"dictionary deltas: ${vectors.map(v => Seq.tabulate(v.getValueCount)(v.apply).mkString(",")).mkString(";")}")
        val transfers = vectors.mapWithIndex { case (v, j) => v.vector.makeTransferPair(dictionaries(j).vector) }
        (vectors, transfers)
      }

      // batch[dictionary[count]]
      val offsets: Array[Array[Int]] = Array.tabulate(toMerge.length) { batch =>
        var i = 0
        val offset = Array.fill(dictionaries.length)(0)
        while (i < batch) {
          // set the count for each batch so we can offset mappings later
          toMerge(i)._1.foreachIndex { case (v, j) => offset(j) += v.getValueCount }
          i += 1
        }
        offset
      }

      val transfers = Array.ofDim[TransferPair](dictionaries.length)
      val mappings = Array.fill(dictionaries.length)(HashBiMap.create[Integer, Integer]())

      var i = 0 // dictionary field index
      while (i < dictionaries.length) {
        // set initial values in the sorting queue
        toMerge.foreachIndex { case ((vectors, _), batch) =>
          if (vectors(i).getValueCount > 0) {
            queue += ((vectors(i).apply(0), batch, 0))
          } else {
            CloseWithLogging(vectors(i).vector)
          }
        }

        var count = 0
        while (queue.nonEmpty) {
          val (_, batch, j) = queue.dequeue()
          val (vectors, transfers) = toMerge(batch)
          transfers(i).copyValueSafe(j, count)
          mappings(i).put(offsets(batch)(i) + j, count)
          val jpp = j + 1
          if (jpp < vectors(i).getValueCount) {
            queue += ((vectors(i).apply(jpp), batch, jpp))
          } else {
            CloseWithLogging(vectors(i).vector)
          }
          count += 1
        }
        dictionaries(i).vector.setValueCount(count)
        transfers(i) = dictionaries(i).vector.makeTransferPair(results(i).vector)
        i += 1
      }

      (dictionaries, transfers, mappings)
    }

    // now merge the separate threads together

    // final mappings - we build up a new map as otherwise we'd get key/value overlaps
    // dictionary(batch(mapping))
    val mappings = Array.fill(results.length)(Array.fill(allMerges.length)(scala.collection.mutable.Map.empty[Integer, Integer]))

    results.foreachIndex { case (result, i) =>
      allMerges.foreachIndex { case ((vectors, _, _), batch) =>
        if (vectors(i).getValueCount > 0) {
          queue += ((vectors(i).apply(0), batch, 0))
        } else {
          CloseWithLogging(vectors(i).vector)
        }
      }

      var count = 0
      while (queue.nonEmpty) {
        val (value, batch, j) = queue.dequeue()
        val (vectors, transfers, mapping) = allMerges.apply(batch)
        // check for duplicates
        if (count == 0 || result.apply(count - 1) != vectors(i).apply(j)) {
          transfers(i).copyValueSafe(j, count)
          count += 1
        }
        // update the dictionary mapping from the per-thread to the global dictionary
        logger.trace(s"remap $value $batch ${mapping(i)} $j -> ${count - 1}")
        val remap = mapping(i).inverse().get(j)
        if (remap != null) {
          mappings(i)(batch).put(remap, count - 1)
        }
        val jpp = j + 1
        if (jpp < vectors(i).getValueCount) {
          queue += ((vectors(i).apply(jpp), batch, jpp))
        } else {
          CloseWithLogging(vectors(i).vector)
        }
      }
      result.vector.setValueCount(count)
    }

    // convert from indexed arrays to dictionary-field-keyed maps
    val dictionaryBuilder = Map.newBuilder[String, ArrowDictionary]
    dictionaryBuilder.sizeHint(dictionaryFields.length)
    val mappingsBuilder = Map.newBuilder[String, Array[scala.collection.Map[Integer, Integer]]]
    mappingsBuilder.sizeHint(dictionaryFields.length)

    dictionaryFields.foreachIndex { case (f, i) =>
      logger.trace("merged dictionary: " + Seq.tabulate(results(i).getValueCount)(results(i).apply).mkString(","))
      val enc = new DictionaryEncoding(i, true, new ArrowType.Int(32, true))
      dictionaryBuilder += ((f, ArrowDictionary.create(enc, results(i).vector, sft.getDescriptor(f), encoding)))
      mappingsBuilder += ((f, mappings(i).asInstanceOf[Array[scala.collection.Map[Integer, Integer]]]))
    }

    val dictionaryMap = dictionaryBuilder.result()
    val mappingsMap = mappingsBuilder.result()

    logger.trace(s"batch dictionary mappings: ${mappingsMap.mapValues(_.mkString(",")).mkString(";")}")
    MergedDictionaries(dictionaryMap, mappingsMap, allocator)
  }

  // holder for merged dictionaries and mappings from written values to merged values
  private case class MergedDictionaries(
      dictionaries: Map[String, ArrowDictionary],
      mappings: Map[String, Array[scala.collection.Map[Integer, Integer]]],
      allocator: BufferAllocator
    ) extends Closeable {
    override def close(): Unit = {
      dictionaries.foreach { case (_, d) => CloseWithLogging(d) }
      CloseWithLogging(allocator)
    }
  }

  private case class FieldWriter(name: String,
                                 index: Int,
                                 attribute: ArrowAttributeWriter,
                                 dictionary: Option[DictionaryWriter] = None)

  private case class DictionaryWriter(index: Int,
                                      attribute: ArrowAttributeWriter,
                                      writer: BatchWriter,
                                      values: scala.collection.mutable.Map[AnyRef, Integer])

  /**
    * Writes out a 4-byte int with the batch length, then a single batch
    *
    * @param vector vector
    */
  private class BatchWriter(vector: FieldVector) extends Closeable {

    private val root = createRoot(vector)
    private val os = new ByteArrayOutputStream()
    private val writer = new ArrowStreamWriter(root, provider, Channels.newChannel(os))
    writer.start() // start the writer - we'll discard the metadata later, as we only care about the record batches

    logger.trace(s"write schema: ${vector.getField}")

    def writeBatch(count: Int, to: OutputStream): Unit = {
      os.reset()
      if (count < 1) {
        logger.trace("writing 0 bytes")
        to.write(ByteArrays.toBytes(0))
      } else {
        vector.setValueCount(count)
        root.setRowCount(count)
        writer.writeBatch()
        logger.trace(s"writing ${os.size} bytes")
        to.write(ByteArrays.toBytes(os.size()))
        os.writeTo(to)
      }
    }

    override def close(): Unit = {
      CloseWithLogging(writer)
      CloseWithLogging(root) // also closes the vector
    }
  }

  private class ReducingIterator(
      sft: SimpleFeatureType,
      dictionaryFields: Seq[String],
      encoding: SimpleFeatureEncoding,
      sort: Option[(String, Boolean)],
      batchSize: Int,
      deltas: CloseableIterator[Array[Byte]]
    ) extends CloseableIterator[Array[Byte]] {

    private val closed = new AtomicBoolean(false)

    private lazy val reduced = {
      val grouped = scala.collection.mutable.Map.empty[Long, scala.collection.mutable.ArrayBuilder[Array[Byte]]]
      while (!closed.get && deltas.hasNext) {
        val delta = deltas.next
        grouped.getOrElseUpdate(ByteArrays.readLong(delta), Array.newBuilder) += delta
      }
      val threaded = Array.ofDim[Array[Array[Byte]]](grouped.size)
      var i = 0
      grouped.foreach { case (_, builder) => threaded(i) = builder.result; i += 1 }
      logger.trace(s"merging delta batches from ${threaded.length} thread(s)")
      val dictionaries = mergeDictionaries(sft, dictionaryFields, threaded, encoding)
      sort match {
        case None => reduceNoSort(sft, dictionaryFields, encoding, dictionaries, batchSize, threaded)
        case Some((s, r)) => reduceWithSort(sft, dictionaryFields, encoding, dictionaries, s, r, batchSize, threaded)
      }
    }

    override def hasNext: Boolean = reduced.hasNext

    override def next(): Array[Byte] = reduced.next()

    override def close(): Unit = {
      closed.set(true)
      CloseWithLogging(deltas, reduced)
    }
  }
}
