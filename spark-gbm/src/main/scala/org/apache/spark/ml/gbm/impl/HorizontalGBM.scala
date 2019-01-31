package org.apache.spark.ml.gbm.impl

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.Random

import org.apache.spark.TaskContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.ml.gbm._
import org.apache.spark.ml.gbm.linalg._
import org.apache.spark.ml.gbm.util._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.util.QuantileSummaries


object HorizontalGBM extends Logging {

  /**
    * implementation of GBM, train a GBMModel, with given types
    *
    * @param trainBlocks  training blocks containing (weight, label, binVec)
    * @param testBlocks   validation blocks containing (weight, label, binVec)
    * @param boostConf    boosting configuration
    * @param discretizer  discretizer to convert raw features into bins
    * @param initialModel inital model
    * @return the model
    */
  def boost[C, B, H](trainBlocks: (RDD[CompactArray[H]], RDD[ArrayBlock[H]], RDD[KVMatrix[C, B]]),
                     testBlocks: Option[(RDD[CompactArray[H]], RDD[ArrayBlock[H]], RDD[KVMatrix[C, B]])],
                     boostConf: BoostConfig,
                     discretizer: Discretizer,
                     initialModel: Option[GBMModel])
                    (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                     cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                     ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): GBMModel = {

    val sc = trainBlocks._1.sparkContext

    // train blocks
    val (trainWeightBlocks, trainLabelBlocks, trainBinVecBlocks) = trainBlocks
    GBM.touchWeightBlocksAndUpdatePartInfo[H](trainWeightBlocks, boostConf, true)
    GBM.touchBinVecBlocksAndUpdateSparsity[C, B](trainBinVecBlocks, boostConf)

    // test blocks
    testBlocks.foreach { case (testWeightBlocks, _, _) =>
      GBM.touchWeightBlocksAndUpdatePartInfo(testWeightBlocks, boostConf, false)
    }


    // init tree buffer
    val weightsBuff = mutable.ArrayBuffer.empty[H]
    val treesBuff = mutable.ArrayBuffer.empty[TreeModel]
    initialModel.foreach { model =>
      weightsBuff.appendAll(neh.fromDouble(model.weights))
      treesBuff.appendAll(model.trees)
    }


    // raw scores and checkpointer
    var trainRawBlocks = GBM.initializeRawBlocks[C, B, H](trainWeightBlocks, trainBinVecBlocks,
      treesBuff.toArray, weightsBuff.toArray, boostConf)
      .setName("Initial: Train RawPreds")
    val trainRawBlocksCheckpointer = new Checkpointer[ArrayBlock[H]](sc,
      boostConf.getCheckpointInterval, boostConf.getStorageLevel2)
    if (treesBuff.nonEmpty) {
      trainRawBlocksCheckpointer.update(trainRawBlocks)
    }


    // raw scores and checkpointer for test data
    var testRawBlocksCheckpointer: Checkpointer[ArrayBlock[H]] = null
    var testRawBlocks = testBlocks.map { case (testWeightBlocks, _, testBinVecBlocks) =>
      testRawBlocksCheckpointer = new Checkpointer[ArrayBlock[H]](sc,
        boostConf.getCheckpointInterval, boostConf.getStorageLevel3)

      val newTestRawBlocks = GBM.initializeRawBlocks[C, B, H](testWeightBlocks, testBinVecBlocks,
        treesBuff.toArray, weightsBuff.toArray, boostConf)
        .setName("Initial: Test RawPreds")
      if (treesBuff.nonEmpty) {
        testRawBlocksCheckpointer.update(newTestRawBlocks)
      }
      newTestRawBlocks
    }


    // metrics history recoder
    val trainMetricsHistory = mutable.ArrayBuffer.empty[Map[String, Double]]
    val testMetricsHistory = mutable.ArrayBuffer.empty[Map[String, Double]]


    // random number generator for drop out
    val dartRng = new Random(boostConf.getSeed)
    val dropped = mutable.Set.empty[Int]

    var iteration = 0
    var finished = false

    while (!finished && iteration < boostConf.getMaxIter) {
      val numTrees = treesBuff.length
      val logPrefix = s"Iter $iteration:"

      // drop out
      if (boostConf.getBoostType == GBM.Dart) {
        GBM.dropTrees(dropped, boostConf, numTrees, dartRng)
        if (dropped.nonEmpty) {
          logInfo(s"$logPrefix ${dropped.size} trees dropped")
        } else {
          logInfo(s"$logPrefix skip drop")
        }
      }


      // build trees
      logInfo(s"$logPrefix start")
      val tic = System.nanoTime()
      val trees = buildTrees[C, B, H](trainWeightBlocks, trainLabelBlocks, trainBinVecBlocks, trainRawBlocks,
        weightsBuff.toArray, boostConf, iteration, dropped.toSet)
      logInfo(s"$logPrefix finished, duration: ${(System.nanoTime() - tic) / 1e9} sec")

      if (trees.forall(_.isEmpty)) {
        // fail to build a new tree
        logInfo(s"$logPrefix no more tree built, GBM training finished")
        finished = true

      } else {
        // update base model buffer
        GBM.updateTreeBuffer(weightsBuff, treesBuff, trees, dropped.toSet, boostConf)

        // whether to keep the weights of previous trees
        val keepWeights = boostConf.getBoostType != GBM.Dart || dropped.isEmpty

        // update train data predictions
        trainRawBlocks = GBM.updateRawBlocks[C, B, H](trainBinVecBlocks, trainRawBlocks,
          trees, weightsBuff.toArray, boostConf, keepWeights)
          .setName(s"Iter $iteration: Train RawPreds")
        trainRawBlocksCheckpointer.update(trainRawBlocks)


        if (boostConf.getEvalFunc.isEmpty) {
          // materialize predictions
          trainRawBlocks.count()
        }

        // evaluate on train data
        if (boostConf.getEvalFunc.nonEmpty) {
          val trainMetrics = GBM.evaluate[H, C, B](trainWeightBlocks, trainLabelBlocks, trainRawBlocks, boostConf)
          trainMetricsHistory.append(trainMetrics)
          logInfo(s"$logPrefix train metrics ${trainMetrics.mkString("(", ", ", ")")}")
        }


        testBlocks.foreach { case (testWeightBlocks, testLabelBlocks, testBinVecBlocks) =>
          // update test data predictions
          val newTestRawBlocks = GBM.updateRawBlocks[C, B, H](testBinVecBlocks, testRawBlocks.get,
            trees, weightsBuff.toArray, boostConf, keepWeights)
            .setName(s"Iter $iteration: Test RawPreds")

          testRawBlocks = Some(newTestRawBlocks)
          testRawBlocksCheckpointer.update(testRawBlocks.get)

          // evaluate on test data
          val testMetrics = GBM.evaluate[H, C, B](testWeightBlocks, testLabelBlocks, testRawBlocks.get, boostConf)
          testMetricsHistory.append(testMetrics)
          logInfo(s"$logPrefix test metrics ${testMetrics.mkString("(", ", ", ")")}")
        }


        // callback
        if (boostConf.getCallbackFunc.nonEmpty) {
          // using cloning to avoid model modification
          val snapshot = new GBMModel(boostConf.getObjFunc, discretizer.copy(),
            boostConf.getRawBaseScore.clone(), treesBuff.toArray.clone(),
            neh.toDouble(weightsBuff.toArray).clone())

          // callback can update boosting configuration
          boostConf.getCallbackFunc.foreach { callback =>
            if (callback.compute(boostConf, snapshot, iteration + 1,
              trainMetricsHistory.toArray.clone(), testMetricsHistory.toArray.clone())) {
              finished = true
              logInfo(s"$logPrefix callback ${callback.name} stop training")
            }
          }
        }
      }

      logInfo(s"$logPrefix finished, ${treesBuff.length} trees now")
      iteration += 1
    }

    if (iteration >= boostConf.getMaxIter) {
      logInfo(s"maxIter=${boostConf.getMaxIter} reached, GBM training finished")
    }

    trainRawBlocksCheckpointer.clear(false)
    if (testRawBlocksCheckpointer != null) {
      testRawBlocksCheckpointer.clear(false)
    }

    new GBMModel(boostConf.getObjFunc, discretizer, boostConf.getRawBaseScore,
      treesBuff.toArray, neh.toDouble(weightsBuff.toArray))
  }


  def buildTrees[C, B, H](weightBlocks: RDD[CompactArray[H]],
                          labelBlocks: RDD[ArrayBlock[H]],
                          binVecBlocks: RDD[KVMatrix[C, B]],
                          rawBlocks: RDD[ArrayBlock[H]],
                          weights: Array[H],
                          boostConf: BoostConfig,
                          iteration: Int,
                          dropped: Set[Int])
                         (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                          cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                          ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    import Utils._

    val numTrees = boostConf.getForestSize * boostConf.getRawSize
    logInfo(s"Iter $iteration: Starting to create next $numTrees trees")

    val treeIdType = Utils.getTypeByRange(numTrees)
    logInfo(s"DataType of TreeId: $treeIdType")

    val nodeIdType = Utils.getTypeByRange(1 << boostConf.getMaxDepth)
    logInfo(s"DataType of NodeId: $nodeIdType")


    (treeIdType, nodeIdType) match {
      case (BYTE, BYTE) =>
        buildTrees2[Byte, Byte, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (BYTE, SHORT) =>
        buildTrees2[Byte, Short, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (BYTE, INT) =>
        buildTrees2[Byte, Int, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (SHORT, BYTE) =>
        buildTrees2[Short, Byte, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (SHORT, SHORT) =>
        buildTrees2[Short, Short, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (SHORT, INT) =>
        buildTrees2[Short, Int, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (INT, BYTE) =>
        buildTrees2[Int, Byte, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (INT, SHORT) =>
        buildTrees2[Int, Short, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case (INT, SHORT) =>
        buildTrees2[Int, Int, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)
    }
  }


  /**
    * build new trees
    *
    * @param rawBlocks previous raw predictions
    * @param weights   weights of trees
    * @param boostConf boosting configuration
    * @param iteration current iteration
    * @param dropped   indices of trees which are selected to drop during building of current tree
    * @return new trees
    */
  def buildTrees2[T, N, C, B, H](weightBlocks: RDD[CompactArray[H]],
                                 labelBlocks: RDD[ArrayBlock[H]],
                                 binVecBlocks: RDD[KVMatrix[C, B]],
                                 rawBlocks: RDD[ArrayBlock[H]],
                                 weights: Array[H],
                                 boostConf: BoostConfig,
                                 iteration: Int,
                                 dropped: Set[Int])
                                (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                 cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                 cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                 cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                 ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    import nuh._

    val sc = weightBlocks.sparkContext

    val cleaner = new ResourceCleaner

    val bcBoostConf = sc.broadcast(boostConf)
    cleaner.registerBroadcastedObjects(bcBoostConf)

    val rawSize = boostConf.getRawSize
    val objFunc = Utils.deepCopy(boostConf.getObjFunc)

    val computeRaw: Array[H] => Array[H] = boostConf.getBoostType match {
      case GBM.GBTree =>
        rawSeq: Array[H] => rawSeq

      case GBM.Dart if dropped.isEmpty =>
        rawSeq: Array[H] => neh.take(rawSeq, rawSize)

      case GBM.Dart if dropped.nonEmpty =>
        val rawBase = neh.fromDouble(boostConf.getRawBaseScore)

        rawSeq: Array[H] =>
          val rawPred = rawBase.clone()
          var i = rawSize
          while (i < rawSeq.length) {
            if (!dropped.contains(i - rawSize)) {
              rawPred(i % rawSize) += rawSeq(i) * weights(i - rawSize)
            }
            i += 1
          }
          rawPred
    }

    val computeGrad = (weight: H, label: Array[H], rawSeq: Array[H]) => {
      val score = objFunc.transform(neh.toDouble(computeRaw(rawSeq)))
      val (grad, hess) = objFunc.compute(neh.toDouble(label), score)
      require(grad.length == rawSize && hess.length == rawSize)

      val array = Array.ofDim[H](rawSize << 1)
      var i = 0
      while (i < rawSize) {
        val j = i << 1
        array(j) = neh.fromDouble(grad(i)) * weight
        array(j + 1) = neh.fromDouble(hess(i)) * weight
        i += 1
      }
      array
    }

    val computeGradBlock = (weightBlock: CompactArray[H], labelBlock: ArrayBlock[H], rawBlock: ArrayBlock[H]) => {
      require(weightBlock.size == rawBlock.size)
      require(labelBlock.size == rawBlock.size)

      val iter = Utils.zip3(weightBlock.iterator, labelBlock.iterator, rawBlock.iterator)
        .map { case (weight, label, rawSeq) => computeGrad(weight, label, rawSeq) }

      ArrayBlock.build[H](iter)
    }


    val treeConfig = TreeConfig.createWithAdjustedColSampling(boostConf, iteration)
    logInfo(s"Iter $iteration: TreeConfig $treeConfig")

    val bcTreeConf = sc.broadcast(treeConfig)
    cleaner.registerBroadcastedObjects(bcTreeConf)


    // To alleviate memory footprint in caching layer, different schemas of intermediate dataset are designed.
    // Each `prepareTreeInput**` method will internally cache necessary datasets in a compact fashion.
    // These cached datasets are holden in `recoder`, and will be freed after training.
    val trees = boostConf.getSubSampleType match {
      case GBM.Goss =>
        buildTreesWithGoss[T, N, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, computeRaw, computeGradBlock,
          boostConf, bcBoostConf, treeConfig, bcTreeConf, cleaner)

      case _ if boostConf.getSubSampleRateByTree == 1 =>
        buildTreesWithNonSampling[T, N, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, computeRaw, computeGradBlock,
          boostConf, bcBoostConf, treeConfig, bcTreeConf, cleaner)

      case GBM.Partition =>
        buildTreesWithPartitionSampling[T, N, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, computeRaw, computeGradBlock,
          boostConf, bcBoostConf, treeConfig, bcTreeConf, cleaner)

      case GBM.Block =>
        buildTreesWithBlockSampling[T, N, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, computeRaw, computeGradBlock,
          boostConf, bcBoostConf, treeConfig, bcTreeConf, cleaner)

      case GBM.Row =>
        buildTreesWithRowSampling[T, N, C, B, H](weightBlocks, labelBlocks, binVecBlocks, rawBlocks, computeRaw, computeGrad,
          boostConf, bcBoostConf, treeConfig, bcTreeConf, cleaner)
    }

    cleaner.clear(false)

    trees
  }


  def buildTreesWithGoss[T, N, C, B, H](weightBlocks: RDD[CompactArray[H]],
                                        labelBlocks: RDD[ArrayBlock[H]],
                                        binVecBlocks: RDD[KVMatrix[C, B]],
                                        rawBlocks: RDD[ArrayBlock[H]],
                                        computeRaw: Array[H] => Array[H],
                                        computeGradBlock: (CompactArray[H], ArrayBlock[H], ArrayBlock[H]) => ArrayBlock[H],
                                        boostConf: BoostConfig,
                                        bcBoostConf: Broadcast[BoostConfig],
                                        treeConf: TreeConfig,
                                        bcTreeConf: Broadcast[TreeConfig],
                                        cleaner: ResourceCleaner)
                                       (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                        cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                        cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                        cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                        ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    import nuh._

    val sc = weightBlocks.sparkContext

    val otherSelector = Selector.create(boostConf.getOtherSampleRate, boostConf.getNumBlocks * boostConf.getBlockSize,
      boostConf.getForestSize, 1, boostConf.getSeed + treeConf.iteration)
    logInfo(s"Iter ${treeConf.iteration}: OtherSelector $otherSelector")

    val bcOtherSelector = sc.broadcast(otherSelector)
    cleaner.registerBroadcastedObjects(bcOtherSelector)


    // here all base models share the same gradient,
    // so we do not need to compute quantile for each base model.
    val gradNormBlocks = weightBlocks.zipPartitions(labelBlocks, rawBlocks) {
      case (weightBlockIter, labelBlockIter, rawBlockIter) =>

        Utils.zip3(weightBlockIter, labelBlockIter, rawBlockIter)
          .map { case (weightBlock, labelBlock, rawBlock) =>
            val gradBlock = computeGradBlock(weightBlock, labelBlock, rawBlock)

            val normBlock = gradBlock.iterator
              .map { gradHess =>
                var gradNorm = nuh.zero
                var i = 0
                while (i < gradHess.length) {
                  gradNorm += gradHess(i) * gradHess(i)
                  i += 2
                }
                gradNorm
              }.toArray

            require(gradBlock.size == normBlock.length)
            (gradBlock, normBlock)
          }
    }.setName(s"Iter ${treeConf.iteration}: GradWithNorms")

    gradNormBlocks.persist(boostConf.getStorageLevel2)
    cleaner.registerCachedRDDs(gradNormBlocks)


    val tic = System.nanoTime()
    logInfo(s"Iter ${treeConf.iteration}: start to compute the threshold of top gradients")

    val summary = gradNormBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>
        var s = new QuantileSummaries(QuantileSummaries.defaultCompressThreshold,
          QuantileSummaries.defaultRelativeError)

        val gradNormIter = iter.flatMap(_._2.iterator).map(nuh.toDouble)
        while (gradNormIter.hasNext) {
          s = s.insert(gradNormIter.next())
        }

        s = s.compress

        if (s.count > 0) {
          Iterator.single(s)
        } else if (partId == 0) {
          // avoid `treeReduce` on empty RDD
          Iterator.single(s)
        } else {
          Iterator.empty
        }

      }.treeReduce(f = _.merge(_).compress,
      depth = boostConf.getAggregationDepth)

    val threshold = neh.fromDouble(summary.query(1 - boostConf.getTopRate).get)
    logInfo(s"Iter ${treeConf.iteration}: threshold for top gradients: ${neh.sqrt(threshold)}, " +
      s"duration ${(System.nanoTime() - tic) / 1e9} seconds")


    val sampledBinVecBlocks = binVecBlocks.zipPartitions(gradNormBlocks) {
      case (binVecBlockIter, gradNormBlockIter) =>
        val partId = TaskContext.getPartitionId()

        val boostConf = bcBoostConf.value
        val blockSize = boostConf.getBlockSize
        var blockId = boostConf.getBlockOffset(partId) - 1

        val treeConf = bcTreeConf.value
        val sliceVec = treeConf.sliceVector[C, B]

        val otherSelector = bcOtherSelector.value

        Utils.zip2(binVecBlockIter, gradNormBlockIter)
          .flatMap { case (binVecBlock, (_, normBlock)) =>
            require(binVecBlock.size == normBlock.length)
            blockId += 1
            var rowId = blockId * blockSize - 1

            Utils.zip2(binVecBlock.iterator, normBlock.iterator)
              .flatMap { case (binVec, norm) =>
                rowId += 1
                if (norm >= threshold ||
                  otherSelector.contains[Long](rowId)) {
                  Iterator.single(sliceVec(binVec))
                } else {
                  Iterator.empty
                }
              }
          }.grouped(blockSize)
          .map(KVMatrix.build[C, B])
    }.setName(s"Iter ${treeConf.iteration}: BinVecs (GOSS)")

    sampledBinVecBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(sampledBinVecBlocks)


    val treeIdBlocks = gradNormBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>
        val boostConf = bcBoostConf.value
        val blockSize = boostConf.getBlockSize
        val numTrees = boostConf.getNumTrees
        val topTreeIds = Array.tabulate(numTrees)(int.fromInt)
        val computeTreeIds = boostConf.computeTreeIds[T]()
        var blockId = boostConf.getBlockOffset(partId) - 1

        val otherSelector = bcOtherSelector.value

        iter.flatMap { case (_, normBlock) =>
          blockId += 1
          var rowId = blockId * blockSize - 1

          normBlock.iterator
            .flatMap { norm =>
              rowId += 1
              if (norm >= threshold) {
                Iterator.single(topTreeIds)
              } else {
                val baseIds = otherSelector.index[T, Long](rowId)
                if (baseIds.nonEmpty) {
                  val treeIds = computeTreeIds(baseIds)
                  Iterator.single(treeIds)
                } else {
                  Iterator.empty
                }
              }
            }
        }.grouped(blockSize)
          .map(ArrayBlock.build[T])
      }.setName(s"Iter ${treeConf.iteration}: TreeIds (GOSS)")

    treeIdBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(treeIdBlocks)


    if (boostConf.getGreedierSearch) {

      val sampledWeightBlocks = weightBlocks.zipPartitions(gradNormBlocks) {
        case (weightBlockIter, gradNormBlockIter) =>
          val partId = TaskContext.getPartitionId()

          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val otherSelector = bcOtherSelector.value

          Utils.zip2(weightBlockIter, gradNormBlockIter)
            .flatMap { case (weightBlock, (_, normBlock)) =>
              require(weightBlock.size == normBlock.length)
              blockId += 1
              var rowId = blockId * blockSize - 1

              Utils.zip2(weightBlock.iterator, normBlock.iterator)
                .flatMap { case (weight, norm) =>
                  rowId += 1
                  if (norm >= threshold ||
                    otherSelector.contains[Long](rowId)) {
                    Iterator.single(weight)
                  } else {
                    Iterator.empty
                  }
                }
            }.grouped(blockSize)
            .map(CompactArray.build[H])
      }.setName(s"Iter ${treeConf.iteration}: Weights (GOSS)")

      sampledWeightBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(sampledWeightBlocks)


      val sampledLabelBlocks = labelBlocks.zipPartitions(gradNormBlocks) {
        case (labelBlockIter, gradNormBlockIter) =>
          val partId = TaskContext.getPartitionId()

          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val otherSelector = bcOtherSelector.value

          Utils.zip2(labelBlockIter, gradNormBlockIter)
            .flatMap { case (labelBlock, (_, normBlock)) =>
              require(labelBlock.size == normBlock.length)
              blockId += 1
              var rowId = blockId * blockSize - 1

              Utils.zip2(labelBlock.iterator, normBlock.iterator)
                .flatMap { case (label, norm) =>
                  rowId += 1
                  if (norm >= threshold ||
                    otherSelector.contains[Long](rowId)) {
                    Iterator.single(label)
                  } else {
                    Iterator.empty
                  }
                }
            }.grouped(blockSize)
            .map(ArrayBlock.build[H])
      }.setName(s"Iter ${treeConf.iteration}: Labels (GOSS)")

      sampledLabelBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(sampledLabelBlocks)


      val sampledRawPredBlocks = rawBlocks.zipPartitions(gradNormBlocks) {
        case (rawBlockIter, gradNormBlockIter) =>
          val partId = TaskContext.getPartitionId()

          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val otherSelector = bcOtherSelector.value

          Utils.zip2(rawBlockIter, gradNormBlockIter)
            .flatMap { case (rawBlock, (_, normBlock)) =>
              require(rawBlock.size == normBlock.length)
              blockId += 1
              var rowId = blockId * blockSize - 1

              Utils.zip2(rawBlock.iterator, normBlock.iterator)
                .flatMap { case (raw, norm) =>
                  rowId += 1
                  if (norm >= threshold ||
                    otherSelector.contains[Long](rowId)) {
                    Iterator.single(computeRaw(raw))
                  } else {
                    Iterator.empty
                  }
                }
            }.grouped(blockSize)
            .map(ArrayBlock.build[H])
      }.setName(s"Iter ${treeConf.iteration}: RawPreds (GOSS)")


      GreedierTree.train[T, N, C, B, H](sampledWeightBlocks, sampledLabelBlocks, sampledBinVecBlocks,
        sampledRawPredBlocks, treeIdBlocks, boostConf, bcBoostConf, treeConf, bcTreeConf)


    } else {

      val gradBlocks = gradNormBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>

          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          val weightScale = neh.fromDouble(boostConf.getOtherReweight)
          var blockId = boostConf.getBlockOffset(partId) - 1

          val otherSelector = bcOtherSelector.value

          iter.flatMap { case (gradBlock, normBlock) =>
            require(gradBlock.size == normBlock.length)
            blockId += 1
            var rowId = blockId * blockSize - 1

            Utils.zip2(gradBlock.iterator, normBlock.iterator)
              .flatMap { case (grad, norm) =>
                rowId += 1
                if (norm >= threshold) {
                  Iterator.single(grad)

                } else if (otherSelector.contains[Long](rowId)) {
                  var i = 0
                  while (i < grad.length) {
                    grad(i) *= weightScale
                    i += 1
                  }
                  Iterator.single(grad)

                } else {
                  Iterator.empty
                }
              }
          }.grouped(blockSize)
            .map(ArrayBlock.build[H])
        }.setName(s"Iter ${treeConf.iteration}: Grads (GOSS)")


      gradBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(gradBlocks)


      HorizontalTree.train[T, N, C, B, H](sampledBinVecBlocks, treeIdBlocks, gradBlocks,
        boostConf, bcBoostConf, treeConf, bcTreeConf)
    }
  }


  def buildTreesWithNonSampling[T, N, C, B, H](weightBlocks: RDD[CompactArray[H]],
                                               labelBlocks: RDD[ArrayBlock[H]],
                                               binVecBlocks: RDD[KVMatrix[C, B]],
                                               rawBlocks: RDD[ArrayBlock[H]],
                                               computeRaw: Array[H] => Array[H],
                                               computeGradBlock: (CompactArray[H], ArrayBlock[H], ArrayBlock[H]) => ArrayBlock[H],
                                               boostConf: BoostConfig,
                                               bcBoostConf: Broadcast[BoostConfig],
                                               treeConf: TreeConfig,
                                               bcTreeConf: Broadcast[TreeConfig],
                                               cleaner: ResourceCleaner)
                                              (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                               cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                               cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                               cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                               ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {

    val treeIdBlocks = weightBlocks
      .mapPartitions { iter =>
        val boostConf = bcBoostConf.value
        val blockSize = boostConf.getBlockSize
        val numTrees = boostConf.getNumTrees
        val treeIds = Array.tabulate(numTrees)(int.fromInt)
        val defaultTreeIdBlock = ArrayBlock.fill(blockSize, treeIds)

        iter.map { weightBlock =>
          if (weightBlock.size == blockSize) {
            defaultTreeIdBlock
          } else {
            ArrayBlock.fill(weightBlock.size, treeIds)
          }
        }
      }.setName(s"Iter ${treeConf.iteration}: TreeIds")

    treeIdBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(treeIdBlocks)


    val sampledBinVecBlocks = if (treeConf.colSampledAhead) {
      val colSampled = binVecBlocks
        .mapPartitions { iter =>
          val treeConf = bcTreeConf.value
          val sliceBlock = treeConf.sliceMatrix[C, B]

          iter.map(sliceBlock)
        }

      colSampled.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(colSampled)
      colSampled

    } else {
      binVecBlocks
    }


    if (boostConf.getGreedierSearch) {

      val rawPredBlocks = boostConf.getBoostType match {
        case GBM.GBTree =>
          rawBlocks

        case _ =>
          rawBlocks.map { rawBlock =>
            val iter = rawBlock.iterator.map(computeRaw)
            ArrayBlock.build[H](iter)
          }
      }
      rawPredBlocks.setName(s"Iter ${treeConf.iteration}: RawPreds")

      GreedierTree.train[T, N, C, B, H](weightBlocks, labelBlocks, sampledBinVecBlocks,
        rawPredBlocks, treeIdBlocks, boostConf, bcBoostConf, treeConf, bcTreeConf)

    } else {

      val gradBlocks = weightBlocks.zipPartitions(labelBlocks, rawBlocks) {
        case (weightBlockIter, labelBlockIter, rawBlockIter) =>

          Utils.zip3(weightBlockIter, labelBlockIter, rawBlockIter)
            .map { case (weightBlock, labelBlock, rawBlock) =>
              computeGradBlock(weightBlock, labelBlock, rawBlock)
            }
      }.setName(s"Iter ${treeConf.iteration}: Grads")

      gradBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(gradBlocks)

      HorizontalTree.train[T, N, C, B, H](sampledBinVecBlocks, treeIdBlocks, gradBlocks,
        boostConf, bcBoostConf, treeConf, bcTreeConf)
    }
  }


  def buildTreesWithPartitionSampling[T, N, C, B, H](weightBlocks: RDD[CompactArray[H]],
                                                     labelBlocks: RDD[ArrayBlock[H]],
                                                     binVecBlocks: RDD[KVMatrix[C, B]],
                                                     rawBlocks: RDD[ArrayBlock[H]],
                                                     computeRaw: Array[H] => Array[H],
                                                     computeGradBlock: (CompactArray[H], ArrayBlock[H], ArrayBlock[H]) => ArrayBlock[H],
                                                     boostConf: BoostConfig,
                                                     bcBoostConf: Broadcast[BoostConfig],
                                                     treeConf: TreeConfig,
                                                     bcTreeConf: Broadcast[TreeConfig],
                                                     cleaner: ResourceCleaner)
                                                    (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                                     cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                                     cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                                     cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                                     ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    val sc = weightBlocks.sparkContext

    val partSelector = Selector.create(boostConf.getSubSampleRateByTree, weightBlocks.getNumPartitions,
      boostConf.getForestSize, 1, boostConf.getSeed + treeConf.iteration)
    logInfo(s"Iter ${treeConf.iteration}: PartSelector $partSelector")

    val bcPartSelector = sc.broadcast(partSelector)
    cleaner.registerBroadcastedObjects(bcPartSelector)


    val sampledBinVecBlocks = if (treeConf.colSampledAhead) {
      val colSampled = binVecBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val treeConf = bcTreeConf.value
          val sliceBlock = treeConf.sliceMatrix[C, B]

          val partSelector = bcPartSelector.value
          if (partSelector.contains[Int](partId)) {
            iter.map(sliceBlock)
          } else {
            Iterator.empty
          }
        }

      colSampled.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(colSampled)
      colSampled

    } else {
      binVecBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val partSelector = bcPartSelector.value
          if (partSelector.contains[Int](partId)) {
            iter
          } else {
            Iterator.empty
          }
        }
    }

    sampledBinVecBlocks.setName(s"Iter ${treeConf.iteration}: BinVecs (Partition-Sampled)")


    val treeIdBlocks = weightBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>

        val boostConf = bcBoostConf.value
        val blockSize = boostConf.getBlockSize

        val partSelector = bcPartSelector.value
        val baseIds = partSelector.index[T, Int](partId)

        if (baseIds.nonEmpty) {
          val computeTreeIds = bcBoostConf.value.computeTreeIds[T]()
          val treeIds = computeTreeIds(baseIds)
          val defaultTreeIdBlock = ArrayBlock.fill(blockSize, treeIds)

          iter.map { weightBlock =>
            if (weightBlock.size == blockSize) {
              defaultTreeIdBlock
            } else {
              ArrayBlock.fill(weightBlock.size, treeIds)
            }
          }
        } else {
          Iterator.empty
        }
      }.setName(s"Iter ${treeConf.iteration}: TreeIds (Partition-Sampled)")

    treeIdBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(treeIdBlocks)


    if (boostConf.getGreedierSearch) {

      val sampledWeightBlocks = weightBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val partSelector = bcPartSelector.value
          if (partSelector.contains[Int](partId)) {
            iter
          } else {
            Iterator.empty
          }
        }.setName(s"Iter ${treeConf.iteration}: Weights (Partition-Sampled)")


      val sampledLabelBlocks = labelBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val partSelector = bcPartSelector.value
          if (partSelector.contains[Int](partId)) {
            iter
          } else {
            Iterator.empty
          }
        }.setName(s"Iter ${treeConf.iteration}: Labels (Partition-Sampled)")


      val sampledRawPredBlocks = boostConf.getBoostType match {
        case GBM.GBTree =>
          rawBlocks
            .mapPartitionsWithIndex { case (partId, iter) =>
              val partSelector = bcPartSelector.value
              if (partSelector.contains[Int](partId)) {
                iter
              } else {
                Iterator.empty
              }
            }

        case _ =>
          rawBlocks
            .mapPartitionsWithIndex { case (partId, iter) =>
              val partSelector = bcPartSelector.value
              if (partSelector.contains[Int](partId)) {
                iter.map { rawBlock =>
                  val iter2 = rawBlock.iterator.map(computeRaw)
                  ArrayBlock.build[H](iter2)
                }
              } else {
                Iterator.empty
              }
            }
      }
      sampledRawPredBlocks.setName(s"Iter ${treeConf.iteration}: RawPreds (Partition-Sampled)")

      GreedierTree.train[T, N, C, B, H](sampledWeightBlocks, sampledLabelBlocks, sampledBinVecBlocks,
        sampledRawPredBlocks, treeIdBlocks, boostConf, bcBoostConf, treeConf, bcTreeConf)

    } else {

      val gradBlocks = weightBlocks.zipPartitions(labelBlocks, rawBlocks) {
        case (weightBlockIter, labelBlockIter, rawBlockIter) =>
          val partId = TaskContext.getPartitionId()

          val partSelector = bcPartSelector.value
          if (partSelector.contains[Int](partId)) {
            Utils.zip3(weightBlockIter, labelBlockIter, rawBlockIter)
              .map { case (weightBlock, labelBlock, rawBlock) =>
                computeGradBlock(weightBlock, labelBlock, rawBlock)
              }
          } else {
            Iterator.empty
          }
      }.setName(s"Iter ${treeConf.iteration}: Grads (Partition-Sampled)")

      gradBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(gradBlocks)

      HorizontalTree.train[T, N, C, B, H](sampledBinVecBlocks, treeIdBlocks, gradBlocks,
        boostConf, bcBoostConf, treeConf, bcTreeConf)
    }
  }


  def buildTreesWithBlockSampling[T, N, C, B, H](weightBlocks: RDD[CompactArray[H]],
                                                 labelBlocks: RDD[ArrayBlock[H]],
                                                 binVecBlocks: RDD[KVMatrix[C, B]],
                                                 rawBlocks: RDD[ArrayBlock[H]],
                                                 computeRaw: Array[H] => Array[H],
                                                 computeGradBlock: (CompactArray[H], ArrayBlock[H], ArrayBlock[H]) => ArrayBlock[H],
                                                 boostConf: BoostConfig,
                                                 bcBoostConf: Broadcast[BoostConfig],
                                                 treeConf: TreeConfig,
                                                 bcTreeConf: Broadcast[TreeConfig],
                                                 cleaner: ResourceCleaner)
                                                (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                                 cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                                 cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                                 cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                                 ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    val sc = weightBlocks.sparkContext

    val blockSelector = Selector.create(boostConf.getSubSampleRateByTree, boostConf.getNumBlocks,
      boostConf.getForestSize, 1, boostConf.getSeed + treeConf.iteration)
    logInfo(s"Iter ${treeConf.iteration}: BlockSelector $blockSelector")

    val bcBlockSelector = sc.broadcast(blockSelector)
    cleaner.registerBroadcastedObjects(bcBlockSelector)


    val sampledBinVecBlocks = binVecBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>
        val boostConf = bcBoostConf.value
        var blockId = boostConf.getBlockOffset(partId) - 1

        val treeConf = bcTreeConf.value
        val sliceBlock = treeConf.sliceMatrix[C, B]

        val blockSelector = bcBlockSelector.value

        iter.flatMap { binVecBlock =>
          blockId += 1
          if (blockSelector.contains[Long](blockId)) {
            Iterator.single(sliceBlock(binVecBlock))
          } else {
            Iterator.empty
          }
        }
      }.setName(s"Iter ${treeConf.iteration}: BinVecs (Block-Sampled)")

    sampledBinVecBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(sampledBinVecBlocks)


    val treeIdBlocks = weightBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>
        val boostConf = bcBoostConf.value
        val computeTreeIds = boostConf.computeTreeIds[T]()
        var blockId = boostConf.getBlockOffset(partId) - 1

        val blockSelector = bcBlockSelector.value

        iter.flatMap { weightBlock =>
          blockId += 1
          val baseIds = blockSelector.index[T, Long](blockId)
          if (baseIds.nonEmpty) {
            val treeIds = computeTreeIds(baseIds)
            Iterator.single(ArrayBlock.fill(weightBlock.size, treeIds))
          } else {
            Iterator.empty
          }
        }
      }.setName(s"Iter ${treeConf.iteration}: TreeIds (Block-Sampled)")

    treeIdBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(treeIdBlocks)


    if (boostConf.getGreedierSearch) {
      val sampledWeightBlocks = weightBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val boostConf = bcBoostConf.value
          var blockId = boostConf.getBlockOffset(partId) - 1

          val blockSelector = bcBlockSelector.value

          iter.flatMap { weightBlock =>
            blockId += 1
            if (blockSelector.contains[Long](blockId)) {
              Iterator.single(weightBlock)
            } else {
              Iterator.empty
            }
          }
        }.setName(s"Iter ${treeConf.iteration}: Weights (Block-Sampled)")

      sampledWeightBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(sampledWeightBlocks)


      val sampledLabelBlocks = labelBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val boostConf = bcBoostConf.value
          var blockId = boostConf.getBlockOffset(partId) - 1

          val blockSelector = bcBlockSelector.value

          iter.flatMap { labelBlock =>
            blockId += 1
            if (blockSelector.contains[Long](blockId)) {
              Iterator.single(labelBlock)
            } else {
              Iterator.empty
            }
          }
        }.setName(s"Iter ${treeConf.iteration}: Weights (Block-Sampled)")

      sampledWeightBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(sampledWeightBlocks)


      val sampledRawPredBlocks = boostConf.getBoostType match {
        case GBM.GBTree =>
          rawBlocks
            .mapPartitionsWithIndex { case (partId, iter) =>
              val boostConf = bcBoostConf.value
              var blockId = boostConf.getBlockOffset(partId) - 1

              val blockSelector = bcBlockSelector.value

              iter.flatMap { rawBlock =>
                blockId += 1
                if (blockSelector.contains[Long](blockId)) {
                  Iterator.single(rawBlock)
                } else {
                  Iterator.empty
                }
              }
            }

        case _ =>
          rawBlocks
            .mapPartitionsWithIndex { case (partId, iter) =>
              val boostConf = bcBoostConf.value
              var blockId = boostConf.getBlockOffset(partId) - 1

              val blockSelector = bcBlockSelector.value

              iter.flatMap { rawBlock =>
                blockId += 1
                if (blockSelector.contains[Long](blockId)) {
                  val iter2 = rawBlock.iterator.map(computeRaw)
                  Iterator.single(ArrayBlock.build[H](iter2))
                } else {
                  Iterator.empty
                }
              }
            }
      }
      sampledRawPredBlocks.setName(s"Iter ${treeConf.iteration}: RawPreds (Block-Sampled)")

      GreedierTree.train[T, N, C, B, H](sampledWeightBlocks, sampledLabelBlocks, sampledBinVecBlocks,
        sampledRawPredBlocks, treeIdBlocks, boostConf, bcBoostConf, treeConf, bcTreeConf)

    } else {

      val gradBlocks = weightBlocks.zipPartitions(labelBlocks, rawBlocks) {
        case (weightBlockIter, labelBlockIter, rawBlockIter) =>
          val partId = TaskContext.getPartitionId()

          var blockId = bcBoostConf.value.getBlockOffset(partId) - 1
          val blockSelector = bcBlockSelector.value

          Utils.zip3(weightBlockIter, labelBlockIter, rawBlockIter)
            .flatMap { case (weightBlock, labelBlock, rawBlock) =>
              blockId += 1
              if (blockSelector.contains[Long](blockId)) {
                val gradBlock = computeGradBlock(weightBlock, labelBlock, rawBlock)
                Iterator.single(gradBlock)
              } else {
                Iterator.empty
              }
            }
      }.setName(s"Iter ${treeConf.iteration}: Grads (Block-Sampled)")

      gradBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(gradBlocks)

      HorizontalTree.train[T, N, C, B, H](sampledBinVecBlocks, treeIdBlocks, gradBlocks,
        boostConf, bcBoostConf, treeConf, bcTreeConf)
    }
  }


  def buildTreesWithRowSampling[T, N, C, B, H](weightBlocks: RDD[CompactArray[H]],
                                               labelBlocks: RDD[ArrayBlock[H]],
                                               binVecBlocks: RDD[KVMatrix[C, B]],
                                               rawBlocks: RDD[ArrayBlock[H]],
                                               computeRaw: Array[H] => Array[H],
                                               computeGrad: (H, Array[H], Array[H]) => Array[H],
                                               boostConf: BoostConfig,
                                               bcBoostConf: Broadcast[BoostConfig],
                                               treeConf: TreeConfig,
                                               bcTreeConf: Broadcast[TreeConfig],
                                               cleaner: ResourceCleaner)
                                              (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                               cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                               cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                               cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                               ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    val sc = weightBlocks.sparkContext

    val rowSelector = Selector.create(boostConf.getSubSampleRateByTree, boostConf.getNumBlocks * boostConf.getBlockSize,
      boostConf.getForestSize, 1, boostConf.getSeed + treeConf.iteration)
    logInfo(s"Iter ${treeConf.iteration}: RowSelector $rowSelector")

    val bcRowSelector = sc.broadcast(rowSelector)
    cleaner.registerBroadcastedObjects(bcRowSelector)


    val sampledBinVecBlocks = binVecBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>
        val boostConf = bcBoostConf.value
        val blockSize = boostConf.getBlockSize
        var blockId = boostConf.getBlockOffset(partId) - 1

        val treeConf = bcTreeConf.value
        val sliceVec = treeConf.sliceVector[C, B]

        val rowSelector = bcRowSelector.value

        iter.flatMap { binVecBlock =>
          blockId += 1
          var rowId = blockId * blockSize - 1

          binVecBlock.iterator
            .flatMap { binVec =>
              rowId += 1
              if (rowSelector.contains[Long](rowId)) {
                Iterator.single(sliceVec(binVec))
              } else {
                Iterator.empty
              }
            }
        }.grouped(blockSize)
          .map(KVMatrix.build[C, B])
      }.setName(s"Iter ${treeConf.iteration}: BinVecs (Row-Sampled)")

    sampledBinVecBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(sampledBinVecBlocks)


    val treeIdBlocks = weightBlocks
      .mapPartitionsWithIndex { case (partId, iter) =>
        val boostConf = bcBoostConf.value
        val blockSize = boostConf.getBlockSize
        val computeTreeIds = boostConf.computeTreeIds[T]()
        var blockId = boostConf.getBlockOffset(partId) - 1

        val rowSelector = bcRowSelector.value

        iter.flatMap { weightBlock =>
          blockId += 1
          var rowId = blockId * blockSize - 1

          Iterator.range(0, weightBlock.size)
            .flatMap { _ =>
              rowId += 1
              val baseIds = rowSelector.index[T, Long](rowId)
              if (baseIds.nonEmpty) {
                val treeIds = computeTreeIds(baseIds)
                Iterator.single(treeIds)
              } else {
                Iterator.empty
              }
            }
        }.grouped(blockSize)
          .map(ArrayBlock.build[T])
      }.setName(s"Iter ${treeConf.iteration}: TreeIds (Row-Sampled)")

    treeIdBlocks.persist(boostConf.getStorageLevel1)
    cleaner.registerCachedRDDs(treeIdBlocks)


    if (boostConf.getGreedierSearch) {

      val sampledWeightBlocks = weightBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val rowSelector = bcRowSelector.value

          iter.flatMap { weightBlock =>
            blockId += 1
            var rowId = blockId * blockSize - 1

            weightBlock.iterator
              .flatMap { weight =>
                rowId += 1
                if (rowSelector.contains[Long](rowId)) {
                  Iterator.single(weight)
                } else {
                  Iterator.empty
                }
              }
          }.grouped(blockSize)
            .map(CompactArray.build[H])
        }.setName(s"Iter ${treeConf.iteration}: Weights (Row-Sampled)")

      sampledWeightBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(sampledWeightBlocks)


      val sampledLabelBlocks = labelBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val rowSelector = bcRowSelector.value

          iter.flatMap { labelBlock =>
            blockId += 1
            var rowId = blockId * blockSize - 1

            labelBlock.iterator
              .flatMap { label =>
                rowId += 1
                if (rowSelector.contains[Long](rowId)) {
                  Iterator.single(label)
                } else {
                  Iterator.empty
                }
              }
          }.grouped(blockSize)
            .map(ArrayBlock.build[H])
        }.setName(s"Iter ${treeConf.iteration}: Labels (Row-Sampled)")

      sampledLabelBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(sampledLabelBlocks)


      val sampledRawPredBlocks = rawBlocks
        .mapPartitionsWithIndex { case (partId, iter) =>
          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val rowSelector = bcRowSelector.value

          iter.flatMap { rawBlock =>
            blockId += 1
            var rowId = blockId * blockSize - 1

            rawBlock.iterator
              .flatMap { raw =>
                rowId += 1
                if (rowSelector.contains[Long](rowId)) {
                  Iterator.single(computeRaw(raw))
                } else {
                  Iterator.empty
                }
              }
          }.grouped(blockSize)
            .map(ArrayBlock.build[H])
        }.setName(s"Iter ${treeConf.iteration}: RawPreds (Row-Sampled)")

      GreedierTree.train[T, N, C, B, H](sampledWeightBlocks, sampledLabelBlocks, sampledBinVecBlocks,
        sampledRawPredBlocks, treeIdBlocks, boostConf, bcBoostConf, treeConf, bcTreeConf)

    } else {

      val gradBlocks = weightBlocks.zipPartitions(labelBlocks, rawBlocks) {
        case (weightBlockIter, labelBlockIter, rawBlockIter) =>
          val partId = TaskContext.getPartitionId()

          val boostConf = bcBoostConf.value
          val blockSize = boostConf.getBlockSize
          var blockId = boostConf.getBlockOffset(partId) - 1

          val rowSelector = bcRowSelector.value

          Utils.zip3(weightBlockIter, labelBlockIter, rawBlockIter)
            .flatMap { case (weightBlock, labelBlock, rawBlock) =>
              require(weightBlock.size == rawBlock.size)
              require(labelBlock.size == rawBlock.size)
              blockId += 1
              var rowId = blockId * blockSize - 1

              Utils.zip3(weightBlock.iterator, labelBlock.iterator, rawBlock.iterator)
                .flatMap { case (weight, label, rawSeq) =>
                  rowId += 1
                  if (rowSelector.contains[Long](rowId)) {
                    val grad = computeGrad(weight, label, rawSeq)
                    Iterator.single(grad)
                  } else {
                    Iterator.empty
                  }
                }
            }.grouped(blockSize)
            .map(ArrayBlock.build[H])
      }.setName(s"Iter ${treeConf.iteration}: Grads (Row-Sampled)")

      gradBlocks.persist(boostConf.getStorageLevel1)
      cleaner.registerCachedRDDs(gradBlocks)

      HorizontalTree.train[T, N, C, B, H](sampledBinVecBlocks, treeIdBlocks, gradBlocks,
        boostConf, bcBoostConf, treeConf, bcTreeConf)
    }
  }
}
