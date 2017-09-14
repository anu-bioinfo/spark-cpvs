package se.uu.farmbio.vs.examples

import org.apache.spark.Logging
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext

import scopt.OptionParser
import se.uu.farmbio.vs.SBVSPipeline

object Docker extends Logging {
  case class Params(
    master: String = null,
    conformersFile: String = null,
    receptorFile: String = null,
    topPosesPath: String = null,
    size: String = "30",
    sampleSize: Double = 1.0,
    posesCheckpointPath: String = null,
    oeLicensePath: String = null,
    topN: Int = 30,
    dockTimePerMol: Boolean = false)

  def main(args: Array[String]) {

    val defaultParams = Params()

    val parser = new OptionParser[Params]("Docker") {
      head("Docker: an example docking pipeline.")
      opt[String]("size")
        .text("it controls how many molecules are handled within a task (default: 30).")
        .action((x, c) => c.copy(size = x))
      opt[String]("sampleSize")
        .text("it reduces the input size to the specified fraction (default: 1.0, means no reduction). " +
          "It can be used to evaluate scalability.")
        .action((x, c) => c.copy(sampleSize = x.toDouble))
      opt[String]("master")
        .text("spark master")
        .action((x, c) => c.copy(master = x))
      opt[String]("posesCheckpointPath")
        .text("path to checkpoint all of the output poses before taking the top 10 (default: null)")
        .action((x, c) => c.copy(posesCheckpointPath = x))
      opt[Int]("topN")
        .text("number of top scoring poses to extract (default: 30).")
        .action((x, c) => c.copy(topN = x))
      opt[Unit]("dockTimePerMol")
        .text("if set the docking time will be saved in the results as a PDB REMARK")
        .action((_, c) => c.copy(dockTimePerMol = true))
      arg[String]("<conformers-file>")
        .required()
        .text("path to input PDBQT conformers file")
        .action((x, c) => c.copy(conformersFile = x))
      arg[String]("<receptor-file>")
        .required()
        .text("path to input PDBQT receptor file")
        .action((x, c) => c.copy(receptorFile = x))
      arg[String]("<top-poses-path>")
        .required()
        .text("path to top output poses")
        .action((x, c) => c.copy(topPosesPath = x))

    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    } getOrElse {
      sys.exit(1)
    }

  }

  def run(params: Params) {

    //Init Spark
    val conf = new SparkConf()
      .setAppName("Docker")
   
    if (params.master != null) {
      conf.setMaster(params.master)
    }
    val sc = new SparkContext(conf)
    sc.hadoopConfiguration.set("se.uu.farmbio.parsers.PDBRecordReader.size", params.size)

    var sampleRDD = new SBVSPipeline(sc)
      .readConformerFile(params.conformersFile)
      .getMolecules

    if (params.sampleSize < 1.0) { //Samples Data on the basis of sampleSize Parameter
      sampleRDD = sampleRDD.sample(false, params.sampleSize) //Does not take effect for complete set
    }
    
    var poses = new SBVSPipeline(sc)
      .readConformerRDDs(Seq(sampleRDD))
      .dock(params.receptorFile, params.dockTimePerMol)
   poses.getMolecules.saveAsTextFile("data/test")   
   /* 
    val cachedPoses = poses.getMolecules.cache()  
    cachedPoses.saveAsTextFile("data/poses")
    
    val res = poses.getTopPoses(params.topN)

    if (params.posesCheckpointPath != null) {
      cachedPoses.saveAsTextFile(params.posesCheckpointPath)
    }
    sc.parallelize(res, 1).saveAsTextFile(params.topPosesPath)
*/
  }

}