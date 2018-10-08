package org.apache.spark.ml.gbm

import java.{util => ju}

import scala.collection.mutable
import scala.reflect.ClassTag

import org.apache.spark.{HashPartitioner, Partitioner}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.util.BoundedPriorityQueue


private[gbm] trait HistogramComputer[T, N, C, B, H] extends Logging {

  def compute(data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
              boostConf: BoostConfig,
              baseConf: BaseConfig,
              splits: Map[(T, N), Split],
              depth: Int)
             (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
              cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
              cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
              cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
              ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])]


  def clear(): Unit = {}

  def destroy(): Unit = {}
}


private[gbm] class BasicHistogramComputer[T, N, C, B, H] extends HistogramComputer[T, N, C, B, H] {

  override def compute(data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
                       boostConf: BoostConfig,
                       baseConf: BaseConfig,
                       splits: Map[(T, N), Split],
                       depth: Int)
                      (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                       cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                       cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                       cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                       ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {

    val sc = data.sparkContext

    val parallelism = boostConf.getRealParallelism(boostConf.getReduceParallelism, sc.defaultParallelism)

    val treeNodeIds = if (depth == 0) {
      Array.tabulate(baseConf.numTrees)(t => (int.fromInt(t), inn.one))

    } else {
      splits.keysIterator.flatMap { case (treeId, nodeId) =>
        val leftNodeId = inn.plus(nodeId, nodeId)
        val rightNodeId = inn.plus(leftNodeId, inn.one)
        Iterator.apply((treeId, leftNodeId), (treeId, rightNodeId))
      }.toArray.sorted
    }

    val partitioner = new IDRangePratitioner[T, N, C](parallelism, boostConf.getNumCols, treeNodeIds)
    logInfo(s"Iteration ${baseConf.iteration}: Depth $depth, partitioner $partitioner")

    val minNodeId = inn.fromInt(1 << depth)
    HistogramComputer.computeHistograms[T, N, C, B, H](data, boostConf, baseConf, (n: N) => inn.gteq(n, minNodeId), partitioner)
  }
}


private[gbm] class SubtractHistogramComputer[T, N, C, B, H] extends HistogramComputer[T, N, C, B, H] {

  private var checkpointer = Option.empty[Checkpointer[((T, N, C), KVVector[B, H])]]

  override def compute(data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
                       boostConf: BoostConfig,
                       baseConf: BaseConfig,
                       splits: Map[(T, N), Split],
                       depth: Int)
                      (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                       cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                       cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                       cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                       ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {

    val sc = data.sparkContext

    val parallelism = boostConf.getRealParallelism(boostConf.getReduceParallelism, sc.defaultParallelism)

    val minNodeId = inn.fromInt(1 << depth)

    val (treeIds, prevPartitioner) = if (depth == 0) {
      checkpointer = Some(new Checkpointer[((T, N, C), KVVector[B, H])](sc, boostConf.getCheckpointInterval, boostConf.getStorageLevel))
      (Array.tabulate(baseConf.numTrees)(int.fromInt), None)

    } else {
      (splits.keysIterator.map(_._1).toArray.distinct.sorted, checkpointer.get.last.partitioner)
    }

    val partitioner = HistogramComputer.updatePartitioner[T, N, C](boostConf, treeIds, depth, parallelism, prevPartitioner)
    logInfo(s"Iteration ${baseConf.iteration}: Depth $depth, minNodeId $minNodeId, partitioner $partitioner")

    val hists = if (depth == 0) {
      // direct compute the histogram of roots
      HistogramComputer.computeHistograms[T, N, C, B, H](data, boostConf, baseConf, (n: N) => true, partitioner)

    } else {
      // compute the histogram of right leaves
      val rightHists = HistogramComputer.computeHistograms[T, N, C, B, H](data, boostConf, baseConf,
        (n: N) => inn.gteq(n, minNodeId) && inn.equiv(inn.rem(n, inn.fromInt(2)), inn.one), partitioner)

      // compute the histogram of both left leaves and right leaves by subtraction
      HistogramComputer.subtractHistograms[T, N, C, B, H](checkpointer.get.last, rightHists, boostConf, partitioner)
    }
    hists.setName(s"Histograms (Iteration ${baseConf.iteration}, depth $depth)")

    checkpointer.get.update(hists)
    hists
  }

  override def destroy(): Unit = {
    checkpointer.foreach(_.clear())
    checkpointer = None
  }
}


private[gbm] class VoteHistogramComputer[T, N, C, B, H] extends HistogramComputer[T, N, C, B, H] {

  private var recoder = Option.empty[ResourceRecoder]

  override def compute(data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
                       boostConf: BoostConfig,
                       baseConf: BaseConfig,
                       splits: Map[(T, N), Split],
                       depth: Int)
                      (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                       cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                       cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                       cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                       ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {

    val sc = data.sparkContext

    val parallelism = boostConf.getRealParallelism(boostConf.getReduceParallelism, sc.defaultParallelism)

    val treeNodeIds = if (depth == 0) {
      recoder = Some(new ResourceRecoder)

      Array.tabulate(baseConf.numTrees)(t => (int.fromInt(t), inn.one))

    } else {
      splits.keysIterator.flatMap { case (treeId, nodeId) =>
        val leftNodeId = inn.plus(nodeId, nodeId)
        val rightNodeId = inn.plus(leftNodeId, inn.one)
        Iterator.apply((treeId, leftNodeId), (treeId, rightNodeId))
      }.toArray.sorted
    }

    val partitioner = new IDRangePratitioner[T, N, C](parallelism, boostConf.getNumCols, treeNodeIds)
    logInfo(s"Iteration ${baseConf.iteration}: Depth $depth, partitioner $partitioner")

    HistogramComputer.computeHistogramsWithVoting[T, N, C, B, H](data, boostConf, baseConf, depth, partitioner, recoder.get)
  }

  override def clear(): Unit = {
    recoder.foreach(_.clear())
  }

  override def destroy(): Unit = {
    recoder.foreach(_.clear())
    recoder = None
  }
}


private[gbm] object HistogramComputer {


  /**
    * Locally compute the histogram of root node or the right leaves with nodeId greater than minNodeId
    *
    * @param data instances appended with nodeId, containing ((bins, treeIds, grad-hess), nodeIds)
    * @param f    function to filter nodeIds
    * @return histogram data containing (treeId, nodeId, columnId, histogram)
    */
  def computeLocalHistograms[T, N, C, B, H](data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
                                            boostConf: BoostConfig,
                                            baseConf: BaseConfig,
                                            f: N => Boolean,
                                            sorted: Boolean = false)
                                           (implicit ct: ClassTag[T], int: Integral[T],
                                            cn: ClassTag[N], inn: Integral[N],
                                            cc: ClassTag[C], inc: Integral[C],
                                            cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                            ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {
    import PairRDDFunctions._

    val ordering = if (sorted) {
      Some(implicitly[Ordering[(T, N, C)]])
    } else {
      None
    }

    data.mapPartitions { iter =>
      val histSums = mutable.OpenHashMap.empty[(T, N), (H, H)]

      iter.flatMap { case ((bins, treeIds, gradHess), nodeIds) =>
        val gradSize = gradHess.length >> 1
        Iterator.range(0, treeIds.length)
          .filter(i => f(nodeIds(i)))
          .flatMap { i =>
            val treeId = treeIds(i)
            val nodeId = nodeIds(i)
            val indexGrad = (i % gradSize) << 1
            val grad = gradHess(indexGrad)
            val hess = gradHess(indexGrad + 1)

            val (g, h) = histSums.getOrElse((treeId, nodeId), (nuh.zero, nuh.zero))
            histSums.update((treeId, nodeId), (nuh.plus(g, grad), nuh.plus(h, hess)))

            // ignore zero-index bins
            bins.activeIter
              .filter { case (colId, _) => baseConf.selector.contains[T, C](treeId, colId) }
              .map { case (colId, bin) => ((treeId, nodeId, colId), (bin, grad, hess)) }
          }

      } ++ histSums.iterator.flatMap { case ((treeId, nodeId), (gradSum, hessSum)) =>
        // make sure all available (treeId, nodeId, colId) tuples are taken into account
        // by the way, store sum of hist in zero-index bin
        Iterator.range(0, boostConf.getNumCols).filter(colId => baseConf.selector.contains[T, Int](treeId, colId))
          .map { colId => ((treeId, nodeId, inc.fromInt(colId)), (inb.zero, gradSum, hessSum)) }
      }

    }.aggregatePartitionsByKey(KVVector.empty[B, H], ordering)(
      seqOp = {
        case (hist, (bin, grad, hess)) =>
          val indexGrad = inb.plus(bin, bin)
          val indexHess = inb.plus(indexGrad, inb.one)
          hist.plus(indexHess, hess)
            .plus(indexGrad, grad)
      }, combOp = _ plus _

    ).mapValues { hist =>
      var nzGradSum = nuh.zero
      var nzHessSum = nuh.zero

      hist.activeIter.foreach { case (bin, v) =>
        if (inb.gt(bin, inb.one)) {
          if (inb.equiv(inb.rem(bin, inb.fromInt(2)), inb.zero)) {
            nzGradSum = nuh.plus(nzGradSum, v)
          } else {
            nzHessSum = nuh.plus(nzHessSum, v)
          }
        }
      }

      hist.minus(inb.zero, nzGradSum)
        .minus(inb.one, nzHessSum)
        .compressed
    }
  }


  /**
    * Compute the histogram of root node or the right leaves with nodeId greater than minNodeId
    *
    * @param data instances appended with nodeId, containing ((bins, treeIds, grad-hess), nodeIds)
    * @param f    function to filter nodeIds
    * @return histogram data containing (treeId, nodeId, columnId, histogram)
    */
  def computeHistograms[T, N, C, B, H](data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
                                       boostConf: BoostConfig,
                                       baseConf: BaseConfig,
                                       f: N => Boolean,
                                       partitioner: Partitioner)
                                      (implicit ct: ClassTag[T], int: Integral[T],
                                       cn: ClassTag[N], inn: Integral[N],
                                       cc: ClassTag[C], inc: Integral[C],
                                       cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                       ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {
    computeLocalHistograms[T, N, C, B, H](data, boostConf, baseConf, f)
      .reduceByKey(partitioner, _ plus _)
  }


  /**
    * Histogram subtraction
    *
    * @param nodeHists  histogram data of parent nodes
    * @param rightHists histogram data of right leaves
    * @return histogram data of both left and right leaves
    */
  def subtractHistograms[T, N, C, B, H](nodeHists: RDD[((T, N, C), KVVector[B, H])],
                                        rightHists: RDD[((T, N, C), KVVector[B, H])],
                                        boostConf: BoostConfig,
                                        partitioner: Partitioner)
                                       (implicit ct: ClassTag[T], int: Integral[T],
                                        cn: ClassTag[N], inn: Integral[N],
                                        cc: ClassTag[C], inc: Integral[C],
                                        cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                        ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {
    val threshold = neh.fromDouble(boostConf.getMinNodeHess * 2)

    // Only if the partitioner is a SkipNodePratitioner or DepthPratitioner, we can preserves
    // the partitioning after changing the nodeId in key
    val preserve1 = nodeHists.partitioner match {
      case Some(_: SkipNodePratitioner[_, _, _]) => true
      case Some(_: DepthPratitioner[_, _, _]) => true
      case _ => false
    }

    val preserve2 = partitioner match {
      case _: SkipNodePratitioner[_, _, _] => true
      case _: DepthPratitioner[_, _, _] => true
      case _ => false
    }

    nodeHists.mapPartitions(f = { iter =>
      iter.map { case ((treeId, parentNodeId, colId), parentHist) =>
        val leftNodeId = inn.plus(parentNodeId, parentNodeId)
        val rightNodeId = inn.plus(leftNodeId, inn.one)
        ((treeId, rightNodeId, colId), parentHist)
      }
    }, preserve1)

      .join(rightHists, partitioner)

      .mapPartitions(f = { iter =>

        iter.flatMap { case ((treeId, rightNodeId, colId), (parentHist, rightHist)) =>
          require(rightHist.len <= parentHist.len)
          val leftNodeId = inn.minus(rightNodeId, inn.one)
          val leftHist = parentHist.minus(rightHist)

          ((treeId, leftNodeId, colId), leftHist) ::
            ((treeId, rightNodeId, colId), rightHist) :: Nil

        }.filter { case (_, hist) =>
          // leaves with hess less than minNodeHess * 2 can not grow furthermore
          val hessSum = hist.activeIter.filter { case (b, _) =>
            inb.equiv(inb.rem(b, inb.fromInt(2)), inb.one)
          }.map(_._2).sum

          nuh.gteq(hessSum, threshold) && hist.nnz > 2
        }

      }, preserve2)
  }


  /**
    * Compute the histogram of root node or the right leaves with nodeId greater than minNodeId
    *
    * @param data instances appended with nodeId, containing ((bins, treeIds, grad-hess), nodeIds)
    * @return histogram data containing (treeId, nodeId, columnId, histogram)
    */
  def computeHistogramsWithVoting[T, N, C, B, H](data: RDD[((KVVector[C, B], Array[T], Array[H]), Array[N])],
                                                 boostConf: BoostConfig,
                                                 baseConf: BaseConfig,
                                                 depth: Int,
                                                 partitioner: Partitioner,
                                                 recoder: ResourceRecoder)
                                                (implicit ct: ClassTag[T], int: Integral[T],
                                                 cn: ClassTag[N], inn: Integral[N],
                                                 cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                                 cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                                 ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[((T, N, C), KVVector[B, H])] = {
    import PairRDDFunctions._

    val sc = data.sparkContext

    val numBaseModels = baseConf.numTrees / boostConf.getRawSize
    val levelSelector = ColumSelector.create(boostConf.getColSampleRateByLevel, boostConf.getNumCols, numBaseModels, boostConf.getRawSize, boostConf.getSeed * baseConf.iteration + depth)
    val unionSelector = ColumSelector.union(Array(baseConf.selector, levelSelector))
    val baseConf2 = new BaseConfig(baseConf.iteration, baseConf.numTrees, unionSelector)

    val minNodeId = inn.fromInt(1 << depth)
    val parallelism = partitioner.numPartitions
    val topK = boostConf.getTopK
    val top2K = topK << 1

    val expectedNumTops = baseConf2.numTrees * (1 << depth) * top2K
    val sorted = expectedNumTops > (1 << 16)

    val localHistograms = computeLocalHistograms[T, N, C, B, H](data, boostConf, baseConf2, (n: N) => inn.gteq(n, minNodeId), sorted)
      .setName(s"Local Histograms${if (sorted) " (Sorted)" else ""}")
    localHistograms.persist(boostConf.getStorageLevel)
    recoder.append(localHistograms)


    val voted = localHistograms.flatMap { case ((treeId, nodeId, colId), hist) =>
      val split = Split.split[H](inc.toInt(colId), hist.toArray, boostConf, baseConf2)
      split.map(s => ((treeId, nodeId), (-s.gain, colId)))

    }.aggregatePartitionsByKey(new BoundedPriorityQueue[(Float, C)](topK))(
      seqOp = _ += _,
      combOp = _ ++= _
    ).setName("Local TopK")

      .aggregateByKey(KVVector.empty[C, Int], parallelism)(
        seqOp = {
          case (votes, localTopK) =>
            var v = votes
            localTopK.iterator.foreach { case (_, colId) => v = v.plus(colId, 1) }
            v
        }, combOp = _ plus _

      ).setName("Global Votes")

      .flatMap { case ((treeId, nodeId), votes) =>
        votes.activeIter.toArray
          .sortBy(_._2).takeRight(top2K)
          .iterator.map { case (colId, _) =>
          ((treeId, nodeId, colId), true)
        }
      }.setName("Global Top2K")


    if (sorted) {
      import RDDFunctions._

      // the following lines in this block is functionally the same as :
      //  localHistograms.join(voted, partitioner)
      //    .mapValues(_._1)
      //    .reduceByKey(partitioner, _ plus _)
      // we use `zipPartitions` here to avoid shuffle of #localHistograms in `join`

      val voted2 = voted.repartitionAndSortWithinPartitions(new HashPartitioner(1))
        .setName("Global Top2K (Single Sorted Partition)")
      require(voted2.getNumPartitions == 1)
      voted2.persist(boostConf.getStorageLevel)
      recoder.append(voted2)


      val numPartitions = localHistograms.getNumPartitions
      val voted3 = voted2.reorgPartitions(Array.ofDim[Int](numPartitions))
        .setName("Global Top2K (Duplicated Sorted Partitions)")
      require(voted3.getNumPartitions == numPartitions)


      localHistograms.zipPartitions(voted3)(f = {
        case (iter1, iter2) => Utils.innerJoinSortedIters(iter1, iter2)
      }).map(t => (t._1, t._2))
        .reduceByKey(partitioner, _ plus _)

    } else {
      val set = voted.map(_._1).collect().toSet
      val bcSet = sc.broadcast(set)
      recoder.append(bcSet)

      localHistograms.mapPartitions { iter =>
        val set = bcSet.value
        iter.filter { case (ids, _) => set.contains(ids) }
      }.reduceByKey(partitioner, _ plus _)
    }
  }


  /**
    * In histogram subtraction, update partitioner for the current depth to avoid shuffle if possible
    *
    * @param treeIds         current treeIds
    * @param depth           current depth
    * @param prevPartitioner previous partitioner
    */
  def updatePartitioner[T, N, C](boostConf: BoostConfig,
                                 treeIds: Array[T],
                                 depth: Int,
                                 parallelism: Int,
                                 prevPartitioner: Option[Partitioner])
                                (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                 cn: ClassTag[N], inn: Integral[N],
                                 cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C]): Partitioner = {
    prevPartitioner match {
      case Some(p: SkipNodePratitioner[_, _, _])
        if p.numPartitions == parallelism && p.treeIds.length == treeIds.length => p

      case Some(p: DepthPratitioner[_, _, _])
        if p.numPartitions == parallelism && p.treeIds.length == treeIds.length => p

      case _ =>

        // ignore nodeId here
        val expectedNumKeys = treeIds.length * boostConf.getNumCols *
          boostConf.getColSampleRateByTree * boostConf.getColSampleRateByLevel

        if (expectedNumKeys >= (parallelism << 3)) {
          new SkipNodePratitioner[T, N, C](parallelism, boostConf.getNumCols, treeIds)

        } else if (depth > 2 && expectedNumKeys * (1 << (depth - 1)) >= (parallelism << 3)) {
          // check the parent level (not current level)
          new DepthPratitioner[T, N, C](parallelism, boostConf.getNumCols, depth - 1, treeIds)

        } else {
          new HashPartitioner(parallelism)
        }
    }
  }
}


/**
  * Partitioner that ignore nodeId in key (treeId, nodeId, colId), this will avoid unnecessary shuffle
  * in histogram subtraction and reduce communication cost in following split-searching.
  */

private[gbm] class SkipNodePratitioner[T, N, C](val numPartitions: Int,
                                                val numCols: Int,
                                                val treeIds: Array[T])
                                               (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                                cn: ClassTag[N], inn: Integral[N],
                                                cc: ClassTag[C], inc: Integral[C]) extends Partitioner {
  require(numPartitions > 0)
  require(numCols > 0)
  require(treeIds.nonEmpty)
  require(treeIds.forall(t => int.gteq(t, int.zero)))
  require(Iterator.range(0, treeIds.length - 1).forall(i => int.lt(treeIds(i), treeIds(i + 1))))

  private val hash = numPartitions * (numCols + int.toInt(treeIds.sum) + int.toInt(treeIds.min) + int.toInt(treeIds.max))

  private val treeInterval = numPartitions.toDouble / treeIds.length

  private val colInterval = treeInterval / numCols

  override def getPartition(key: Any): Int = key match {
    case null => 0

    case (treeId: T, _: N, colId: C) =>
      val i = net.search(treeIds, treeId)
      require(i >= 0, s"Can not index key $treeId in ${treeIds.mkString("[", ",", "]")}")

      val p = i * treeInterval + inc.toInt(colId) * colInterval
      math.min(numPartitions - 1, p.toInt)
  }

  override def equals(other: Any): Boolean = other match {
    case p: SkipNodePratitioner[T, N, C] =>
      numPartitions == p.numPartitions && numCols == p.numCols &&
        treeIds.length == p.treeIds.length &&
        Iterator.range(0, treeIds.length).forall(i => treeIds(i) == p.treeIds(i))

    case _ =>
      false
  }

  override def hashCode: Int = hash

  override def toString: String = {
    s"SkipNodePratitioner[${ct.runtimeClass.toString.capitalize}, ${cn.runtimeClass.toString.capitalize}, ${cc.runtimeClass.toString.capitalize}]" +
      s"(numPartitions=$numPartitions, numCols=$numCols, treeIds=${treeIds.mkString("[", ",", "]")})"
  }
}


/**
  * Partitioner that will map nodeId into certain depth before partitioning:
  * if nodeId is of depth #depth, just keep it;
  * if nodeId is a descendant of depth level, map it to its ancestor in depth #depth;
  * otherwise, throw an exception
  * this will avoid unnecessary shuffle in histogram subtraction and reduce communication cost in following split-searching.
  */
private[gbm] class DepthPratitioner[T, N, C](val numPartitions: Int,
                                             val numCols: Int,
                                             val depth: Int,
                                             val treeIds: Array[T])
                                            (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                             cn: ClassTag[N], inn: Integral[N],
                                             cc: ClassTag[C], inc: Integral[C]) extends Partitioner {
  require(numPartitions > 0)
  require(numCols > 0)
  require(depth > 1)
  require(treeIds.nonEmpty)
  require(treeIds.forall(t => int.gteq(t, int.zero)))
  require(Iterator.range(0, treeIds.length - 1).forall(i => int.lt(treeIds(i), treeIds(i + 1))))

  private val lowerBound: Int = 1 << depth

  private val upperBound: Int = lowerBound << 1

  private val hash = numPartitions * depth * (numCols + int.toInt(treeIds.sum) + int.toInt(treeIds.min) + int.toInt(treeIds.max))

  private val treeInterval = numPartitions.toDouble / treeIds.length

  private val nodeInterval = treeInterval / lowerBound

  private val colInterval = nodeInterval / numCols

  override def getPartition(key: Any): Int = key match {
    case null => 0

    case (treeId: T, nodeId: N, colId: C) =>
      val i = net.search(treeIds, treeId)
      require(i >= 0, s"Can not index key $treeId in ${treeIds.mkString("[", ",", "]")}")

      val nodeId2 = adjust(inn.toInt(nodeId))

      val p = i * treeInterval + (nodeId2 - lowerBound) * nodeInterval + inc.toDouble(colId) * colInterval
      math.min(numPartitions - 1, p.toInt)
  }

  private def adjust(nodeId: Int): Int = {
    require(nodeId >= lowerBound, s"nodeId $nodeId < lowerBound $lowerBound")
    var n = nodeId
    while (n >= upperBound) {
      n >>= 1
    }
    n
  }

  override def equals(other: Any): Boolean = other match {
    case p: DepthPratitioner[T, N, C] =>
      numPartitions == p.numPartitions &&
        numCols == p.numCols && depth == p.depth &&
        treeIds.length == p.treeIds.length &&
        Iterator.range(0, treeIds.length).forall(i => treeIds(i) == p.treeIds(i))

    case _ =>
      false
  }

  override def hashCode: Int = hash

  override def toString: String = {
    s"DepthPratitioner[${ct.runtimeClass.toString.capitalize}, ${cn.runtimeClass.toString.capitalize}, ${cc.runtimeClass.toString.capitalize}]" +
      s"(numPartitions=$numPartitions, numCols=$numCols, depth=$depth, treeIds=${treeIds.mkString("[", ",", "]")})"
  }
}


/**
  * Partitioner that partition the keys (treeId, nodeId, colId) by order, this will
  * reduce communication cost in following split-searching.
  */
private[gbm] class IDRangePratitioner[T, N, C](val numPartitions: Int,
                                               val numCols: Int,
                                               val treeNodeIds: Array[(T, N)])
                                              (implicit ct: ClassTag[T], int: Integral[T],
                                               cn: ClassTag[N], inn: Integral[N],
                                               cc: ClassTag[C], inc: Integral[C],
                                               order: Ordering[(T, N)]) extends Partitioner {
  require(numPartitions > 0)
  require(numCols > 0)
  require(treeNodeIds.nonEmpty)
  require(Iterator.range(0, treeNodeIds.length - 1).forall(i => order.lt(treeNodeIds(i), treeNodeIds(i + 1))))

  private val hash = {
    val treeIds = treeNodeIds.map(_._1)
    val nodeIds = treeNodeIds.map(_._2)
    numPartitions * (numCols + int.toInt(treeIds.sum) + int.toInt(treeIds.min) + int.toInt(treeIds.max)
      + inn.toInt(nodeIds.sum) + inn.toInt(nodeIds.max) + inn.toInt(nodeIds.min))
  }

  private val nodeInterval = numPartitions.toDouble / treeNodeIds.length

  private val colInterval = nodeInterval / numCols

  override def getPartition(key: Any): Int = key match {
    case null => 0

    case (treeId: T, nodeId: N, colId: C) =>
      val i = ju.Arrays.binarySearch(treeNodeIds, (treeId, nodeId), order.asInstanceOf[ju.Comparator[(T, N)]])
      require(i >= 0, s"Can not index key ${(treeId, nodeId)} in ${treeNodeIds.mkString("[", ",", "]")}")

      val p = i * nodeInterval + inc.toDouble(colId) * colInterval
      math.min(numPartitions - 1, p.toInt)
  }

  override def equals(other: Any): Boolean = other match {
    case p: IDRangePratitioner[T, N, C] =>
      numPartitions == p.numPartitions &&
        numCols == p.numCols && treeNodeIds.length == p.treeNodeIds.length &&
        Iterator.range(0, treeNodeIds.length).forall(i => order.equiv(treeNodeIds(i), p.treeNodeIds(i)))

    case _ =>
      false
  }

  override def hashCode: Int = hash

  override def toString: String = {
    s"IDRangePratitioner[${ct.runtimeClass.toString.capitalize}, ${cn.runtimeClass.toString.capitalize}, ${cc.runtimeClass.toString.capitalize}]" +
      s"(numPartitions=$numPartitions, numCols=$numCols, treeNodeIds=${treeNodeIds.mkString("[", ",", "]")})"
  }
}