/*
 * Copyright 2019 Qubole, Inc.  All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qubole.spark.hiveacid.writer

import com.qubole.shaded.hadoop.hive.ql.io.BucketCodec

import scala.collection.JavaConverters._
import scala.language.implicitConversions
import com.qubole.spark.hiveacid._
import com.qubole.spark.hiveacid.hive.HiveAcidMetadata
import com.qubole.spark.hiveacid.writer.hive.{HiveAcidFullAcidWriter, HiveAcidInsertOnlyWriter, HiveAcidWriterOptions}
import com.qubole.spark.hiveacid.transaction._
import com.qubole.spark.hiveacid.util.SerializableConfiguration
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, Row, SparkSession, SqlUtils, functions}
import org.apache.spark.sql.catalyst.{InternalRow, TableIdentifier}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericRowWithSchema}
import org.apache.spark.sql.execution.command.AlterTableAddPartitionCommand
import org.apache.spark.sql.execution.datasources.PartitioningUtils
import org.apache.spark.sql.types.StructType

import scala.collection.mutable

/**
 * Performs eager write of a dataframe df to a hive acid table based on operationType
 *
 * @param sparkSession    - Spark session
 * @param curTxn           - Transaction object to acquire locks.
 * @param hiveAcidMetadata - Hive acid table where we want to write dataframe
 * @param statementId      - Optional. In a same transaction, multiple statements like INSERT/UPDATE/DELETE
 *                           (like in case of MERGE) can be issued.
 *                           [[statementId]] have to be different for them to ensure delta collision
 *                           is avoided for them during writes.
 */
private[hiveacid] class TableWriter(sparkSession: SparkSession,
                                    curTxn: HiveAcidTxn,
                                    hiveAcidMetadata: HiveAcidMetadata,
                                    sparkAcidConf: SparkAcidConf,
                                    statementId: Option[Int] = None) extends Logging {

  private val MAX_NUMBER_OF_BUCKETS = 4096

  private def getSchema(operationType: HiveAcidOperation.OperationType): StructType = {
    val expectRowIdsInDataFrame = operationType match {
      case HiveAcidOperation.INSERT_OVERWRITE | HiveAcidOperation.INSERT_INTO => false
      case HiveAcidOperation.DELETE | HiveAcidOperation.UPDATE => true
      case _ => throw HiveAcidErrors.invalidOperationType(operationType.toString)
    }

    if (expectRowIdsInDataFrame) {
      hiveAcidMetadata.tableSchemaWithRowId
    } else {
      hiveAcidMetadata.tableSchema
    }
  }

  private def getColumns(operationType: HiveAcidOperation.OperationType,
            df: DataFrame): (Seq[Attribute], Array[Attribute], Seq[Attribute]) = {

    val columnNames = getSchema(operationType).fields.map(_.name)

    val allColumns = df.queryExecution.optimizedPlan.output.zip(columnNames).map {
      case (attr, columnName) =>
        attr.withName(columnName)
    }

    val allColumnNameToAttrMap = allColumns.map(attr => attr.name -> attr).toMap

    val partitionColumns = hiveAcidMetadata.partitionSchema.fields.map(
      field => allColumnNameToAttrMap(field.name))

    val dataColumns = allColumns.filterNot(partitionColumns.contains)

    (allColumns, partitionColumns, dataColumns)
  }

  /**
    * Common utility function to process all types of operations insert/update/delete
    * for the hive acid table
    * @param operationType type of operation.
    * @param df data frame to be written into the table.
    */
  def process(operationType: HiveAcidOperation.OperationType,
              df: DataFrame): Unit = {

    val hadoopConf = sparkSession.sessionState.newHadoopConf()

    val (allColumns, partitionColumns, dataColumns) = getColumns(operationType, df)
    logInfo(s"Table schema for operationType: ${operationType} as per HiveAcidMetadata: ${getSchema(operationType)}")
    logInfo(s"Dataframe schema for operationType: ${operationType}: ${df.queryExecution.executedPlan.schema}")
    logInfo(s"For operationType: ${operationType}, " +
      s"allColumns: ${allColumns.map(_.name)} \n\n partitionColumns: ${partitionColumns.map(_.name)} \n\ndataColumns: ${dataColumns.map(_.name)}")

    try {

      // FIXME: IF we knew the partition then we should
      //   only lock that partition.
      curTxn.acquireLocks(hiveAcidMetadata, operationType, Seq(), sparkAcidConf)

      if (!HiveAcidTxn.IsTxnStillValid(curTxn, hiveAcidMetadata.fullyQualifiedName)) {
        logInfo(s"Transaction ${curTxn.txnId} is no more valid for table ${hiveAcidMetadata.fullyQualifiedName} as other" +
          s" transaction might have got committed before lock got acquired")
        HiveAcidErrors.txnOutdated(curTxn.txnId, hiveAcidMetadata.fullyQualifiedName)
      }

      // Create Snapshot !!!
      val curSnapshot = HiveAcidTxn.createSnapshot(curTxn, hiveAcidMetadata)

      val writerOptions = new WriterOptions(curSnapshot.currentWriteId,
        operationType,
        new SerializableConfiguration(hadoopConf),
        getSchema(operationType),
        dataColumns,
        partitionColumns,
        allColumns,
        sparkSession.sessionState.conf.sessionLocalTimeZone,
        statementId
      )

      val isFullAcidTable = hiveAcidMetadata.isFullAcidTable

      val hiveAcidWriterOptions = HiveAcidWriterOptions.get(hiveAcidMetadata, writerOptions)

      // This RDD is serialized and sent for distributed execution.
      // All the access object in this needs to be serializable.
      val processRddPartition = new (Iterator[InternalRow] => Seq[TablePartitionSpec]) with
        Serializable {
        override def apply(iterator: Iterator[InternalRow]): Seq[TablePartitionSpec] = {
          val writer = if (isFullAcidTable) {
            new HiveAcidFullAcidWriter(writerOptions, hiveAcidWriterOptions)
          } else {
            new HiveAcidInsertOnlyWriter(writerOptions, hiveAcidWriterOptions)
          }
          iterator.foreach { row => writer.process(row) }
          writer.close()
          writer.partitionsTouched()
        }
      }

      val resultRDD =
        operationType match {
          // In order to read data from delete deltas, hive uses mergesort, which requires
          // originalWriteId, bucket, and rowId in ascending order, and currentWriteId in descending order.
          // We take care of originalWriteId, bucket, and rowId in asc order here. We only write file per bucket-transaction,
          // hence currentWriteId remains same throughout the file and doesn't need ordering.
          //
          // Deleted rowId needs to be in same bucketed file name as the original row. To achieve this,
          // we repartition into 4096 partitions (i.e maximum number of buckets) based on bucket Id.
          // This ensures all rows of one bucket goes to same partition.
          //
          //  ************** Repartitioning Logic *******************
          //
          // rowId.bucketId is composed of following.

          // top 3 bits - version code.
          // next 1 bit - reserved for future
          // next 12 bits - the bucket ID
          // next 4 bits reserved for future
          // remaining 12 bits - the statement ID - 0-based numbering of all statements within a
          // transaction.  Each leg of a multi-insert statement gets a separate statement ID.
          // The reserved bits align it so that it easier to interpret it in Hex.
          //
          // We need to repartition only on the basis of 12 bits representing bucketID
          // We extract by
          // rowId.bucketId OR 268369920 (0b00001111111111110000000000000000) >>> (rightshift) by 16 bits
          //
          // There is still a chance that rows from multiple buckets go to same partition as well, but this is expected to work!
          case HiveAcidOperation.DELETE | HiveAcidOperation.UPDATE =>

            val bucketExpr = "shiftRightUnsigned(rowId.bucketId & 268369920, 16)"

            val baseDf = df.repartition(MAX_NUMBER_OF_BUCKETS, functions.expr(bucketExpr))
            val baseRdd = baseDf.rdd

            val newDF = SqlUtils.createDataFrameUsingAttributes(sparkSession, baseRdd,
              baseDf.schema, baseDf.queryExecution.analyzed.output)
            val returnDF = newDF.
              sortWithinPartitions("rowId.writeId", "rowId.bucketId", "rowId.rowId").toDF

            val returnRdd = returnDF.queryExecution.executedPlan.execute()
            logInfo(s"Dataframe schema of returnDF: ${returnDF.queryExecution.executedPlan.schema}")

            val fetchBucketInfo = new ((Int,Iterator[Row]) => Iterator[(Int, mutable.Set[(Int, Int, Int, Int)])])
              with Serializable {
              override def apply(partNum: Int, iter: Iterator[Row]): Iterator[(Int, mutable.Set[(Int, Int, Int, Int)])] = {
                val set: mutable.Set[(Int, Int, Int, Int)] = mutable.Set.empty[(Int, Int, Int, Int)]
                iter.foreach {
                  elem: Row => {
                    val genericRow = elem.asInstanceOf[GenericRowWithSchema]
                    val bucketProperty = genericRow.getAs[GenericRowWithSchema]("rowId")
                      .getAs[Int]("bucketId")
                    val bucketID = BucketCodec.V1.decodeWriterId(bucketProperty)
                    val statementId = BucketCodec.V1.decodeStatementId(bucketProperty)
                    val bucketShiftRightCol = elem.getAs[Int]("bucketShiftRightCol")
                    set.add((bucketProperty, bucketID, statementId, bucketShiftRightCol))

                  }
                }
                Iterator((partNum, set))
              }
            }

            val repartitionedDFValues = newDF.withColumn("bucketShiftRightCol",
              functions.expr(bucketExpr)).rdd.mapPartitionsWithIndex {
              fetchBucketInfo
            }.collect()

            val sortedWithInPartitionDFValues = newDF
              .sortWithinPartitions("rowId.writeId", "rowId.bucketId", "rowId.rowId")
              .withColumn("bucketShiftRightCol",
                functions.expr(bucketExpr)).rdd.mapPartitionsWithIndex {
              fetchBucketInfo
            }.collect()

            repartitionedDFValues.foreach {
              case (part: Int, set:  mutable.Set[(Int, Int, Int, Int)]) => {
                set.foreach {
                  case (bucketProperty, bucketID, stmId, bucketShiftRightCol) =>
                    logInfo(s"repartitionedDFValues -> partition: ${part}, bucketProperty: ${bucketProperty}, " +
                      s"bucketID: ${bucketID}," +
                      s" statementID: ${stmId}, bucketShiftRightCol: ${bucketShiftRightCol}")
                }
              }
              case _ => //
            }

            sortedWithInPartitionDFValues.foreach {
              case (part: Int, set:  mutable.Set[(Int, Int, Int, Int)]) => {
                set.foreach {
                  case (bucketProperty, bucketID, stmId, bucketShiftRightCol) =>
                    logInfo(s"sortedWithInPartitionDFValues -> partition: ${part}, bucketProperty: ${bucketProperty}, " +
                      s"bucketID: ${bucketID}," +
                      s" statementID: ${stmId}, bucketShiftRightCol: ${bucketShiftRightCol}")
                }
              }
              case _ => //
            }
            returnRdd
          case HiveAcidOperation.INSERT_OVERWRITE | HiveAcidOperation.INSERT_INTO =>
            df.queryExecution.executedPlan.execute()
          case unknownOperation =>
            throw HiveAcidErrors.invalidOperationType(unknownOperation.toString)
        }

      logInfo(s"Write Operation being performed to table ${hiveAcidMetadata.tableName}: $operationType")

      val touchedPartitions = sparkSession.sparkContext.runJob(
        resultRDD, processRddPartition
      ).flatten.toSet

      // Add new partition to table metadata under the transaction.
      val existingPartitions = hiveAcidMetadata.getRawPartitions()
        .map(_.getSpec)
        .map(_.asScala.toMap)

      val newPartitions = touchedPartitions -- existingPartitions

      logDebug(s"existing partitions: ${touchedPartitions.size}, " +
        s"partitions touched: ${touchedPartitions.size}, " +
        s"new partitions to add to metastore: ${newPartitions.size}")

      if (newPartitions.nonEmpty) {
        AlterTableAddPartitionCommand(
          new TableIdentifier(hiveAcidMetadata.tableName, Option(hiveAcidMetadata.dbName)),
          newPartitions.toSeq.map(p => (p, None)),
          ifNotExists = true).run(sparkSession)
        // FIXME: Add the notification events for replication et al.
        //
        logDebug("new partitions added successfully")
      }
      if (touchedPartitions.size > 0) {
        val touchedPartitionNames = touchedPartitions.map (
          PartitioningUtils.getPathFragment(_, partitionColumns))

        curTxn.addDynamicPartitions(curSnapshot.currentWriteId, hiveAcidMetadata.dbName,
          hiveAcidMetadata.tableName, operationType, touchedPartitionNames)
      }

    } catch {
      case e: Exception =>
        logError("Exception", e)
        throw e
    }
  }
}