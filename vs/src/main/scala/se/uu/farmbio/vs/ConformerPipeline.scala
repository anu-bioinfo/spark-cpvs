package se.uu.farmbio.vs

import java.io.PrintWriter
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.io.Source
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkFiles
import java.io.InputStream
import org.apache.commons.io.IOUtils
import java.io.FileOutputStream
import java.io.BufferedOutputStream

trait ConformerTransforms {

  def dock(cppExePath: String, method: Int, resolution: Int, receptor: InputStream): SBVSPipeline with PoseTransforms
  def repartition: SBVSPipeline with ConformerTransforms

}

private[vs] class ConformerPipeline(override val rdd: RDD[String])
    extends SBVSPipeline(rdd) with ConformerTransforms {

  //The Spark built-in pipe splits molecules line by line, we need a custom one
  def pipe(command: List[String]) = {

    val res = rdd.map { sdf =>
      //Start executable
      val pb = new ProcessBuilder(command.asJava)
      val proc = pb.start
      // Start a thread to print the process's stderr to ours
      new Thread("stderr reader") {
        override def run() {
          for (line <- Source.fromInputStream(proc.getErrorStream).getLines) {
            System.err.println(line)
          }
        }
      }.start
      // Start a thread to feed the process input 
      new Thread("stdin writer") {
        override def run() {
          val out = new PrintWriter(proc.getOutputStream)
          out.println(sdf)
          out.close()
        }
      }.start
      //Return results as a single string
      Source.fromInputStream(proc.getInputStream).mkString
    }

    new ConformerPipeline(res)

  }

  override def dock(cppExePath: String, method: Int, resolution: Int, receptor: InputStream) = {

    val receptorBytes = IOUtils.toByteArray(receptor)
    val bcastReceptor = sc.broadcast(receptorBytes)
    rdd.mapPartitions(
      { iter =>
        val bos = new BufferedOutputStream(new FileOutputStream("tempPath/receptor.oeb"))
        Stream.continually(bos.write(bcastReceptor.value))
        bos.close
        iter
      })

    val pipedRDD = this.pipe(List(cppExePath, method.toString(), resolution.toString(), "tempPath/receptor.oeb")).getMolecules
    val res = pipedRDD.flatMap(SBVSPipeline.splitSDFmolecules)
    new PosePipeline(res)

    //throw new NotImplementedException("Needs to be re-implemented due to memory issue")
  }

  override def repartition() = {
    val res = rdd.repartition(defaultParallelism)
    new ConformerPipeline(res)
  }

}