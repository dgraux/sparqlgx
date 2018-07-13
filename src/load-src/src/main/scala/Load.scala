import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapred.lib.MultipleTextOutputFormat;
import org.apache.hadoop.io.compress.GzipCodec
import org.apache.log4j.Level
import org.apache.log4j.Level;
import org.apache.log4j.Logger
import org.apache.log4j.Logger;
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import scala.Tuple2;
import scala.util.matching.Regex
import java.io._
import java.security.MessageDigest


class RDDMultipleTextOutputFormat extends MultipleTextOutputFormat[Any, Any] {
    override def generateActualKey(key: Any, value: Any): Any = 
          NullWritable.get()

      override def generateFileNameForKeyValue(key: Any, value: Any, name: String): String = 
            key.asInstanceOf[String]+"/"+name
}

object Main {

  var stat_size = 500 ;

  var debug = false ;

  def merge(xs: List[(Long,String)], ys: List[(Long,String)],n:Int): List[(Long,String)] = {
    var i = n;
    var x = xs ;
    var y = ys ;
    var res : List[(Long,String)] = Nil ;
    while(i > 0)
    {
      i=i-1;
      (x, y) match {
        case(Nil, Nil) => 
        case(Nil, yh::yq) => res = yh::res ; y = yq
        case(xh::xq, Nil) => res = xh::res ; x = xq
        case(xh :: xq, yh :: yq) =>
          if (xh._1 > yh._1) {
              res = xh::res ;
              x = xq ; 
            }
          else {
              res = yh::res ;
              y = yq ;
          }
      }
    }
    var rev : List[(Long,String)] = Nil ;
    while(res != Nil) {
      res match {
        case Nil =>
        case(h::t) => 
          rev = h::rev ;
          res = t 
      }
    }
    rev
  }

  def path_for_IRI(iri: String) {
    if(iri.length() < 150) {
      ("p"+iri.toLowerCase.map{ 
        case c =>
          if( (c<'a' || c>'z') && (c<'0' || c>'9'))
            '_'
          else 
            c})
      }
    else
        ("ps_"+MessageDigest.getInstance("SHA-1").digest(iri.getBytes)) ;    
  }

  def load(input:RDD[(String,String,String)], path:String) {
    val T = input.map {
      case (s,p,o) => 
        (path_for_IRI(p),(s+" "+o))
    }
    T.saveAsHadoopFile(path,classOf[String],classOf[String],classOf[RDDMultipleTextOutputFormat],classOf[GzipCodec])
  }


  def stat(input:RDD[(String,String,String)], path:String) {
    val output = new BufferedWriter(new FileWriter(path))
    val stat_size_cst = stat_size ;
    val stat = input
      .flatMap{ case (s,p,o) => List(((0,s),1l),((1,p),1l),((2,o),1l)) }
      .reduceByKey(_+_).map { t => (t._1._1,(t._2,t._1._2))} // Compute word count
      .aggregateByKey( (Nil:List[(Long,String)],0) ) ( // Compute the stat_size_cst most present per key (and key is s or p or o)
      { case ((acc,size),el) =>  (merge(el::Nil,acc,stat_size_cst),(size+1) min stat_size_cst) },
      { case ((a1,s1),(a2,s2)) => (merge(a1,a2,stat_size_cst),((s1+s2) min stat_size_cst)) }
    ).collect.sortWith{case (a,b) => a._1<b._1 }
    output.write(stat(0)._2._2.toString+" "+stat(1)._2._2.toString+" "+stat(2)._2._2.toString+"\n");
    for(i <- 0 to 2) {
      val list_el=stat(i)._2._1;
      val last = if(stat(i)._2._2 < stat_size_cst) {"*"} else {list_el.last._2};
      list_el foreach { case (n,iri) => val v = if(iri==last) "*" else iri ;  output.write (v+" "+n.toString+"\n") } ;
    }
    output.close()
  }

  def fullstat(input:RDD[(String,String,String)], path:String) {
    val output = new BufferedWriter(new FileWriter(path)) ;
    val stat_size_cst = stat_size ;
    val stat = input.flatMap{ case (s,p,o) => List(((0,p,s),1l),((1,p,o),1l))}
      .reduceByKey(_+_).map { t => ((t._1._2,t._1._1),(t._2,t._1._3))} // Compute word count
      .aggregateByKey( (Nil:List[(Long,String)],0l,0l,0l) ) ( //
      { case ((acc,size,nbDif,total),el) => (merge(el::Nil,acc,stat_size_cst),(size+1) min stat_size_cst,nbDif+1,total+el._1) },
      { case ((a1,s1,n1,t1),(a2,s2,n2,t2)) => (merge(a1,a2,stat_size_cst),((s1+s2) min stat_size_cst),n1+n2,t1+t2) }
    ).collect.foreach {
      case ((pred,col),(statlist,size,nbDif,total)) => 
        if(size<stat_size_cst) {
          output.write(pred+" "+col+" "+(size+1).toString+" "+0+" "+total+"\n");
          statlist foreach { case (n,iri) => output.write(n.toString+" "+iri+"\n") } ;
          output.write("0 *\n");
        }
        else {
          output.write(pred+" "+col+" "+size.toString+" "+(nbDif-size+1).toString+" "+total+"\n");
          val last = statlist.last._2;
          statlist foreach { case (n,iri) => val v = if(iri==last) "*" else iri ;  output.write(n.toString+" "+v+"\n") } ;
        }
    }
    output.close()
  }

  def prefixSearch(dict: IndexedSeq[String], word: String) : Int = {
    
    var beg = 1 ;
    var end = dict.size ;
    var last_ok = 0 ;
    var id = 0 ;
    while( id < word.length() && beg < end)
    {

      // [beg;end[ corresponds to the range of dict that share
      // the letters word.substring(0,id)


      // this first dichotomy will find the new beg such that
      // [beg;end'[ shares word.substring(0,id+1)
      var cur_end = end ;
      var cur_beg = beg ;
      if( dict(beg).length() <= id  || dict(beg).charAt(id) != word.charAt(id)) {
        while(cur_end > beg+1) {
          val mid = (cur_end+beg)/2 ;
          if( (dict(mid).length() > id && dict(mid).charAt(id) >= word.charAt(id)) 
              || (dict(mid).length() <= id && dict(mid) > word) ) {
            cur_end = mid ;
          }
          else {
            beg = mid ;
          }
        }
        beg = beg+1;
        }

      // this second dichotomy will find the new end such that
      // [beg;end[ shares word.substring(0,id+1)
      while(end > cur_beg+1) {
        val mid = (end+cur_beg)/2 ;
        if( (dict(mid).length() > id && dict(mid).charAt(id) > word.charAt(id)) 
          || (dict(mid).length() <= id && dict(mid) > word) ) {
          end = mid ;
        }
        else {
          cur_beg = mid ;
        }
      }

      while( beg < end && word.startsWith(dict(beg)) ) {
        last_ok = beg ;
        beg = beg+1 ;
      }
      id=id+1;
    }
    return last_ok ;
  }

  def prefixReplace( dict : Array[String], s:String) : String = {
    if(s(0) == '<' || s(s.length()-1) == '>') {
      val search = s.substring(1,s.length()-1)
      val id = prefixSearch(dict,search);
      if(dict(id).length > 0 && search.startsWith(dict(id))) {// this test should be useless
        return BigInt(id).toString(36)+":"+search.substring(dict(id).length(),search.length());
      }
    }
    return s;
  }
  
  def countPrefix( input:RDD[(String,Long)], step:Int, target:Long, dict:org.apache.spark.broadcast.Broadcast[scala.collection.immutable.IndexedSeq[String]] ) : Array[String] = {
    return input.map{ case (word,nb) => 
      val curprefix = prefixSearch(dict.value,word) ;
      val pre_length = dict.value(curprefix).length ;
      val length = pre_length + step ;
      if(length>word.length) {
        ((-1,""),0l)
      } else {
        ((curprefix,word.substring(pre_length,length)),nb)
      }
      }.reduceByKey(_+_)
        .filter{ case (key,count) => (count>target) || (step==0 && count>1) }
        .collect()
        .map { case ((pre,add),nb) => if(pre>=0) { dict.value(pre).concat(add) } else { add}}
  }
  
  def prefix(input:RDD[(String,String,String)], path:String, sc : SparkContext) : RDD[(String,String,String)] = {
    val output = new BufferedWriter(new FileWriter(path)) ;
    val wc = input
      .flatMap{ case (s,p,o) => List(s,o) }
      .filter{ case s => s.charAt(0) == '<' && s.charAt(s.length()-1) == '>'}
      .map{ case s => (s.substring(1,s.length()-1),1l) }.reduceByKey(_+_)
    wc.persist()
    val nbLines = wc.map{ case (w,n) => n}.reduce(_+_) ;    
    val target : Long = nbLines / 2 / stat_size ;

    var curSize = 128 ;
    var curDict = Array("") ;
    var lastDict = curDict ; 
    while (curSize > 0) {
      curSize /= 2 ;
      val dict : scala.collection.immutable.IndexedSeq[String] = curDict.toIndexedSeq ;
      val curS = curSize ;
      val bc_dict = sc.broadcast(dict);
      lastDict = countPrefix(wc,curS, target, bc_dict) ;
      curDict = (curDict ++lastDict).sortWith(_<_) ;
//      println(curSize.toString)
    }


    val prefixS = lastDict.sortWith(_<_) ; //.sortWith( (p1,p2) => p1.length > p2.length ) ;

    for( id <- 0 to prefixS.length-1 ) {
      output.write(BigInt(id).toString(36)+" "+prefixS(id)+"\n") 
    }
    output.close()
   
    val bc_prefix=sc.broadcast(prefixS) ;
    val nbExecutors = 100 ;
    val sizePartitionMax = 1000000 ; 
    def nbPartitions(n: Long) : Int = {
      // ensure that there are nbExecutors partions unless 
      // we need really few partitions
      if(nbExecutors*sizePartitionMax > n && 5*n > nbExecutors*sizePartitionMax) { 
        nbExecutors
      }
      else {
       (n/sizePartitionMax).asInstanceOf[Int]+1
      }
    }
    val nbPartitionsTotal : Int = nbPartitions(nbLines);

    val partitionInterval = input
      .map{ case (s,p,o) => (p,1) }
      .reduceByKey(_+_)
      .map{ case (p,n) => (prefixReplace(bc_prefix.value,p),nbPartitions(n)) }
      .collect
      .foldLeft( (0,Map[String,(Int,Int)]()) ) { 
        case ((allocated,map),(p,n)) => 
          ((allocated+n),(map+(prefixReplace(bc_prefix.value,p)->(allocated,n))))
      }._2 ; 
    val res = input
      .map { 
      case (s,p,o) => 
        (prefixReplace(bc_prefix.value,s),
         prefixReplace(bc_prefix.value,p),
         prefixReplace(bc_prefix.value,o))
    }
      .map { case s => (s,null) }  
      .partitionBy(new Partitioner {
      def numPartitions: Int = nbPartitionsTotal
      def getPartition(key: Any): Int = {
        val keys = key.asInstanceOf[(String,String,String)] ;
        val h = keys.hashCode() ;
        var interval = partitionInterval(keys._2) ;
        return (interval._1+Math.abs(h%interval._2))%nbPartitionsTotal ;
      }
      }).reduceByKey( (x,y) => x).map(_._1) ; 
    // 10^6 lines per partition should be less than ~64 Mo  per partition before the predicate partitionning
    res.persist()
  }



  def main(args: Array[String]) {
    // Cut of spark logs.
    Logger.getLogger("org").setLevel(Level.OFF);
    Logger.getLogger("akka").setLevel(Level.OFF);
    val reg = new Regex("\\s*.\\s*$") ;
    val conf = new SparkConf().setAppName("Simple Application");
    val sc = new SparkContext(conf);
    
    var load_path : Option[String] = None ;
    var stat_path : Option[String] = None ;
    var fullstat_path : Option[String] = None ;
    var prefix_path : Option[String] = None ;
    var tripleFile = "" ;
    var uniq = false ;
    var curArg = 0 ;
    while(curArg < args.length) {
      args(curArg) match {
        case "--load" =>
          if(curArg+1 == args.length)
            throw new Exception("No hdfs-path to load given!");
          load_path=Some(args(curArg+1))
          curArg+=1;
        case "--stat-size" =>
          if(curArg+1 == args.length)
            throw new Exception("No stat size given!");
          stat_size=args(curArg+1).toInt ;
          curArg+=1 ;
        case "--stat" =>
          if(curArg+1 == args.length)
            throw new Exception("No file to store stats given!");
          stat_path=Some(args(curArg+1)) ;
          curArg+=1 ;
        case "--full-stat" =>
          if(curArg+1 == args.length)
            throw new Exception("No file to store full stats given!");
          fullstat_path=Some(args(curArg+1)) ;
          curArg+=1 ;
        case "--prefix" =>
          if(curArg+1 == args.length)
            throw new Exception("No file to store prefixes given!");
          prefix_path=Some(args(curArg+1)) ;
          curArg+=1 ;
        case "--clean" =>
          uniq = true ;
        case "--no-clean" =>
          uniq = false ;
        case "--debug" =>
          debug = true ;
        case s =>
          if(tripleFile != "")
            throw new Exception("Invalid command line (two triple files given : "+s+" and "+tripleFile+")!");
          tripleFile = s
        }
        curArg+=1 ;
    }
    
    if(debug) { println("Starting"); }
    val dirty_input : RDD[(String,String,String)]= sc.textFile(tripleFile).map{ 
      line =>           
      val field:Array[String]=line.split("\\s+",3); 
      if(field.length!=3) {
        throw new RuntimeException("Invalid line: "+line);
      }
      (field(0),field(1),reg.replaceFirstIn(field(2),""))
    } ;

  val input = dirty_input ;
 //   if(uniq) {dirty_input.distinct().persist() } else {dirty_input.persist()};

   val prefixed_input = (prefix_path match {
     case None => input
     case Some(path) => prefix(input,path,sc)
   })
    if(debug) { println("Prefix done"); }

    load_path match {
      case None => ()
      case Some(path) => load(prefixed_input,path)
    }
    if(debug) { println("Load done"); }

    stat_path match {
      case None => ()
      case Some(path) => stat(prefixed_input,path)
    }
    if(debug) { println("Stat done"); }

    fullstat_path match {
      case None => ()
      case Some(path) => fullstat(prefixed_input,path)
    }
    if(debug) { println("Full stat done"); }
    sc.stop() ;
  }
}
