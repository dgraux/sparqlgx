import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.log4j.Logger
import org.apache.log4j.Level
import scala.util.matching.Regex
import org.apache.hadoop.mapred.lib.MultipleTextOutputFormat;
import scala.Tuple2;

class RDDMultipleTextOutputFormat[A, B] extends MultipleTextOutputFormat[A, B] {

    override
    protected def generateFileNameForKeyValue(key:A, value:B, name:String):String = {
        return key.toString()+"/"+name
    }
}


object Load {
  def main(args: Array[String]) {
    // Cut of spark logs.
    Logger.getLogger("org").setLevel(Level.OFF);
    Logger.getLogger("akka").setLevel(Level.OFF);
    val reg = new Regex("\\s+.\\s*$") ;
    val conf = new SparkConf().setAppName("Simple Application");
    val sc = new SparkContext(conf);
    val T = sc.textFile(args(0)).map{line => val field:Array[String]=line.split("\\s+",3); if(field.length!=3){throw new RuntimeException("Invalid line: "+line);}else{("p"+field(1).toLowerCase.map{ case c =>
          if( (c<'a' || c>'z') && (c<'0' || c>'9')){ '_'} else {c}
          },(field(0),reg.replaceFirstIn(field(2),"")))}}.cache;

    val confhadoop = sc.hadoopConfiguration
    val fshadoop = org.apache.hadoop.fs.FileSystem.get(confhadoop)
    T.saveAsHadoopFile(args(1),classOf[String],classOf[String],classOf[RDDMultipleTextOutputFormat[String,String]])
    }
}
