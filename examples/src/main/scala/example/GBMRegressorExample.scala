package example

import org.apache.spark.sql._
import org.apache.spark.ml.regression._
import org.apache.spark.ml.evaluation.RegressionEvaluator

/**
  * spark-submit --class example.GBMRegressorExample --jars gbm/target/gbm-0.0.2.jar examples/target/examples-0.0.2.jar 2>log
  */
object GBMRegressorExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .appName("GBMRegressorExample")
      .getOrCreate

    spark.sparkContext.setLogLevel("INFO")

    val checkpointDir = s"/tmp/sparkGBM/spark-checkpoint-${System.nanoTime}"
    spark.sparkContext.setCheckpointDir(checkpointDir)

    val data = spark.read.format("libsvm").load("data/housing_scale")

    val Array(train, test) = data.randomSplit(Array(0.8, 0.2), seed = 123)

    val gbmr = new GBMRegressor
    gbmr.setBoostType("dart")
      .setStepSize(0.1)
      .setMaxIter(20)
      .setDropRate(0.1)
      .setDropSkip(0.5)
      .setCheckpointInterval(10)
      .setSeed(System.currentTimeMillis)

    /** train without validation */
    val model = gbmr.fit(train)

    /** weights generated by DART */
    println(s"weights of trees: ${model.weights.mkString("(", ",", ")")}")

    /** rmse of model with all trees */
    val evaluator = new RegressionEvaluator()
    evaluator.setMetricName("rmse")

    val rmse1 = evaluator.evaluate(model.transform(train))
    println(s"RMSE on train data $rmse1")

    val rmse2 = evaluator.evaluate(model.transform(test))
    println(s"RMSE on test data $rmse2")

    spark.stop()
  }
}

