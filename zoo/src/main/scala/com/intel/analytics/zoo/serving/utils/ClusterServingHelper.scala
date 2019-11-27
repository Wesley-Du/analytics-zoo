/*
 * Copyright 2018 Analytics Zoo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.intel.analytics.zoo.serving.utils

import java.io.{File, FileInputStream}

import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.models.utils.ModelBroadcast
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.pipeline.api.keras.layers.utils.EngineRef
import com.intel.analytics.zoo.pipeline.api.Net
import com.intel.analytics.zoo.pipeline.inference.InferenceModel
import java.util.LinkedHashMap
import org.apache.log4j.Logger
import scopt.OptionParser
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession
import org.yaml.snakeyaml.Yaml

import scala.reflect.ClassTag

case class LoaderParams(modelFolder: String = null,
                        batchSize: Int = 4,
                        topN: Int = 1,
                        redis: String = "localhost:6379",
                        dataShape: String = "3, 224, 224",

                        // not used temporarily
                        modelType: String = null,
                        isInt8: Boolean = false,
                        outputPath: String = "",
                        task: String = "image-classification")


case class Result(id: String, value: String)

class ClusterServingHelper {
  type HM = LinkedHashMap[String, String]
//  val parser = new OptionParser[LoaderParams]("Cluster Serving") {
//
//    opt[String]('t', "modelType")
//      .text("Model type, could be caffe, keras")
//      .action((x, c) => c.copy(modelType = x))
//
//    opt[String]('f', "modelFolder")
//      .text("Weight file path")
//      .action((x, c) => c.copy(modelFolder = x))
//      .required()
//
//    opt[String]('r', "redis")
//      .text("Redis url")
//      .action((x, c) => c.copy(redis = x))
//
//    opt[Int]('b', "batchSize")
//      .text("Inference batch size")
//      .action((x, c) => c.copy(batchSize = x))
//    opt[String]('o', "outputPath")
//      .text("Redis url")
//      .action((x, c) => c.copy(outputPath = x))
//    opt[Int]('n', "top-N")
//      .text("number of return in classification task")
//      .action((x, c) => c.copy(topN = x))
//    opt[String]('s', "shape")
//      .text("Data shape")
//      .action((x, c) => c.copy(dataShape = x))
//
//  }
  val logger: Logger = Logger.getLogger(getClass)

  var sc: SparkContext = null
//  var params: LoaderParams = null
  var redisHost: String = null
  var redisPort: String = null
  var batchSize: Int = 4
  var topN: Int = 1
  var nodeNum: Int = 1
  var coreNum: Int = 1
  var engineType: String = null
  var blasFlag: Boolean = false

  var dataShape = Array[Int]()


  var modelType: String = null
  var weightPath: String = null
  var defPath: String = null
  var dirPath: String = null


  var dummyMap: Map[Int, String] = Map()

  def initArgs(): Unit = {
//  def initArgs(args: Array[String]): LoaderParams = {
//    params = parser.parse(args, LoaderParams()).get
//
//    require(params.redis.split(":").length == 2, "Your redis host " +
//      "and port are not valid, please check.")
//    redisHost = params.redis.split(":").head.trim
//    redisPort = params.redis.split(":").last.trim
//    batchSize = params.batchSize
//    topN = params.topN
//
//    val shapeList = params.dataShape.split(",")
//    require(shapeList.size == 3, "Your data shape must has dimension as 3")
//    for (i <- shapeList) {
//      dataShape = dataShape :+ i.trim.toInt
//    }
//
//    parseModelType(params.modelFolder)
//    if (modelType == "caffe" || modelType == "bigdl") {
//      if (System.getProperty("bigdl.engineType", "mklblas")
//        .toLowerCase() == "mklblas") {
//        blasFlag = true
//      }
//    }
//
//    params


    val yamlParser = new Yaml()
//    val input = new FileInputStream(new File("zoo/src/" +
//      "main/scala/com/intel/analytics/zoo/serving/config.yaml"))
    val input = new FileInputStream(new File("config.yaml"))
    val configList = yamlParser.load(input).asInstanceOf[HM]

    // parse model field
    val modelConfig = configList.get("model").asInstanceOf[HM]
    val modelFolder = getYaml(modelConfig, "path", null)
    parseModelType(modelFolder)

    // parse data field
    val dataConfig = configList.get("data").asInstanceOf[HM]
    val redis = getYaml(dataConfig, "src", "localhost:6379")
    require(redis.split(":").length == 2, "Your redis host " +
      "and port are not valid, please check.")
    redisHost = redis.split(":").head.trim
    redisPort = redis.split(":").last.trim
    val shape = getYaml(dataConfig, "shape", "3,224,224")
    val shapeList = shape.split(",")
    require(shapeList.size == 3, "Your data shape must has dimension as 3")
    for (i <- shapeList) {
      dataShape = dataShape :+ i.trim.toInt
    }

    val paramsConfig = configList.get("params").asInstanceOf[HM]
    batchSize = getYaml(paramsConfig, "batch_size", "4").toInt
    // engine Type need to be used on executor so do not set here
//    engineType = getYaml(paramsConfig, "engine_type", "mklblas")
    topN = getYaml(paramsConfig, "top_n", "5").toInt

    if (modelType == "caffe" || modelType == "bigdl") {
      if (System.getProperty("bigdl.engineType", "mklblas")
        .toLowerCase() == "mklblas") {
        blasFlag = true
      }
    }

  }

  def getYaml(configList: HM, key: String, default: String): String = {

    val configValue = if (configList.get(key).isInstanceOf[java.lang.Integer]) {
      String.valueOf(configList.get(key))
    } else {
      configList.get(key)
    }

    if (configValue == null) {
      if (default == null) throw new Error(configList.toString + key + " must be provided")
      else {
        return default
      }
    }
    else {
      logger.info(configList.toString + key + " getted: " + configValue)
      return configValue
    }
  }


  def initContext(): Unit = {
    val conf = NNContext.createSparkConf().setAppName("Cluster Serving")
      .set("spark.redis.host", redisHost)
      .set("spark.redis.port", redisPort)
    sc = NNContext.initNNContext(conf)
    nodeNum = EngineRef.getNodeNumber()
    coreNum = EngineRef.getCoreNumber()

  }

  /**
   * Inference Model do not use this method for model loading
   * This method is kept for future, not used now
   * @param ev
   * @tparam T
   * @return
   */
  def loadModel[T: ClassTag]()
                            (implicit ev: TensorNumeric[T]): RDD[Module[Float]] = {
    // deprecated
    val rmodel = modelType match {
      case "caffe" => Net.loadCaffe[Float](defPath, weightPath)
      case "tensorflow" => Net.loadTF[Float](weightPath)
      case "torch" => Net.loadTorch[Float](weightPath)
      case "bigdl" => Net.loadBigDL[Float](weightPath)
      case "keras" => Net.load[Float](weightPath)
    }
    val model = rmodel.quantize().evaluate()


    val bcModel = ModelBroadcast[Float]().broadcast(sc, model)
    val cachedModel = sc.range(1, 100, EngineRef.getNodeNumber())
      .coalesce(EngineRef.getNodeNumber())
      .mapPartitions(v => Iterator.single(bcModel.value(false, true))).cache()
    cachedModel
  }

  def loadInferenceModel(): InferenceModel = {
    val parallelNum = if (blasFlag) coreNum else 1
    val model = new InferenceModel(parallelNum)

    // quantize model would not bring any drawback here
    // but some test may fail at this place
    // perhaps machine not supporting DNN would not accept quantize
    modelType match {
      case "caffe" => model.doLoadCaffe(defPath, weightPath, blas = blasFlag)
      case "bigdl" => model.doLoad(weightPath, blas = blasFlag)

      case "tensorflow" => model.doLoadTF(weightPath, coreNum, 1, true)
      case "pytorch" => model.doLoadPyTorch(weightPath)
      case "keras" => logError("Keras currently not supported in Cluster Serving")
      case "openvino" => model.doLoadOpenVINO(defPath, weightPath, batchSize)
      case _ => logError("Invalid model type, please check your model directory")
    }
    model

  }

  def getSparkSession(): SparkSession = {
    SparkSession
      .builder
      .master(sc.master)
      .config("spark.redis.host", redisHost)
      .config("spark.redis.port", redisPort)
      .getOrCreate()
  }

  /**
   * To check if there already exists detected defPath or weightPath
   * @param defPath Boolean, true means need to check if it is not null
   * @param weightPath Boolean, true means need to check if it is not null
   */
  def throwOneModelError(modelType: Boolean,
                         defPath: Boolean, weightPath: Boolean): Unit = {

    if ((modelType && this.modelType != null) ||
        (defPath && this.defPath != null) ||
        (weightPath && this.weightPath != null)) {
      logError("Only one model is allowed to exist in " +
        "model folder, please check your model folder to keep just" +
        "one model in the directory")

    }
  }

  def logError(msg: String): Unit = {
    logger.error(msg)
    throw new Error(msg)
  }

  def parseModelType(location: String): Unit = {

    import java.io.File
    val f = new File(location)
    val fileList = f.listFiles

    if (modelType == null) {

      for (file <- fileList) {
        val fName = file.getName
        val fPath = new File(location, fName).toString
        if (fName.endsWith("caffemodel")) {
          throwOneModelError(true, false, true)
          weightPath = fPath
          modelType = "caffe"
        }
        else if (fName.endsWith("prototxt")) {
          throwOneModelError(false, true, false)
          defPath = fPath
        }
        // ckpt seems not supported
        else if (fName.endsWith("pb")) {
          throwOneModelError(true, false, true)
          weightPath = location
          modelType = "tensorflow"
        }
        else if (fName.endsWith("pt")) {
          throwOneModelError(true, false, true)
          weightPath = fPath
          modelType = "pytorch"
        }
        else if (fName.endsWith("model")) {
          throwOneModelError(true, false, true)
          weightPath = fPath
          modelType = "bigdl"
        }
        else if (fName.endsWith("keras")) {
          throwOneModelError(true, false, true)
          weightPath = fPath
          modelType = "keras"
        }
        else if (fName.endsWith("bin")) {
          throwOneModelError(true, false, true)
          weightPath = fPath
          modelType = "openvino"
        }
        else if (fName.endsWith("xml")) {
          throwOneModelError(false, true, false)
          defPath = fPath
        }

      }
      if (modelType == null) logError("You did not specify modelType before running" +
        " and the model type could not be inferred from the path" +
        "Note that you should put only one model in your model directory" +
        "And if you do not specify the modelType, it will be inferred " +
        "according to your model file extension name")
    }
    else {
      modelType = modelType.toLowerCase
    }

  }

}
