package stackoverflow

import java.util.stream.Collectors

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import annotation.tailrec
import scala.reflect.ClassTag

/** A raw stackoverflow posting, either a question or an answer */
case class Posting(postingType: Int, id: Int, acceptedAnswer: Option[Int], parentId: Option[QID], score: Int, tags: Option[String]) extends Serializable


/** The main class */
object StackOverflow extends StackOverflow {

  @transient lazy val conf: SparkConf = new SparkConf().setMaster("local").setAppName("StackOverflow")
  @transient lazy val sc: SparkContext = new SparkContext(conf)

  /** Main function */
  def main(args: Array[String]): Unit = {

    val lines = sc.textFile("src/main/resources/stackoverflow/stackoverflow.csv")
    val raw = rawPostings(lines)
    val grouped = groupedPostings(raw)
    val scored = scoredPostings(grouped)
    val vectors = vectorPostings(scored)
    assert(vectors.count() == 2121822, "Incorrect number of vectors: " + vectors.count())

    val means = kmeans(sampleVectors(vectors), vectors, debug = true)
    val results = clusterResults(means, vectors)
    printResults(results)
  }
}


/** The parsing and kmeans methods */
class StackOverflow extends Serializable {

  /** Languages */
  val langs =
    List(
      "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
      "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  /** K-means parameter: How "far apart" languages should be for the kmeans algorithm? */
  def langSpread = 50000

  assert(langSpread > 0, "If langSpread is zero we can't recover the language from the input data!")

  /** K-means parameter: Number of clusters */
  def kmeansKernels = 45

  /** K-means parameter: Convergence criteria */
  def kmeansEta: Double = 20.0D

  /** K-means parameter: Maximum iterations */
  def kmeansMaxIterations = 120


  //
  //
  // Parsing utilities:
  //
  //

  /** Load postings from the given file */
  def rawPostings(lines: RDD[String]): RDD[Posting] =
    lines.map(line => {
      val arr = line.split(",")
      Posting(postingType = arr(0).toInt,
        id = arr(1).toInt,
        acceptedAnswer = if (arr(2) == "") None else Some(arr(2).toInt),
        parentId = if (arr(3) == "") None else Some(arr(3).toInt),
        score = arr(4).toInt,
        tags = if (arr.length >= 6) Some(arr(5).intern()) else None)
    })


  /** Group the questions and answers together */
  def groupedPostings(postings: RDD[Posting]): RDD[(QID, Iterable[(Question, Answer)])] = {
    val questions: RDD[Posting] = postings.filter(p => p.postingType == 1)
    val answers: RDD[Posting] = postings.filter(p => p.postingType == 2 && p.parentId.isDefined)

    val answersPair: RDD[(QID, Answer)] = answers.map(a => {
      (a.parentId.get, a)
    })

    val questionsPair: RDD[(QID, Question)] = questions.map(q => {
      (q.id, q)
    })

    val qaPair: RDD[(QID, (Question, Answer))] = questionsPair.join(answersPair)

    qaPair.groupByKey()
  }


  /** Compute the maximum score for each posting */
  def scoredPostings(grouped: RDD[(QID, Iterable[(Question, Answer)])]): RDD[(Question, HighScore)] = {

    /*def answerHighScore(as: Array[Answer]): HighScore = {
      as.map(_.score).max
    }*/

    grouped.map( g => {
      (g._2.head._1, g._2.map( _._2.score ).max )
    })

  }


  /** Compute the vectors for the kmeans */
  def vectorPostings(scored: RDD[(Question, HighScore)]): RDD[(LangIndex, HighScore)] = {
    /** Return optional index of first language that occurs in `tags`. */
    def firstLangInTag(tag: Option[String], ls: List[String]): Option[Int] = {
      if (tag.isEmpty) None
      else if (ls.isEmpty) None
      else if (tag.get == ls.head) Some(0) // index: 0
      else {
        val tmp = firstLangInTag(tag, ls.tail)
        tmp match {
          case None => None
          case Some(i) => Some(i + 1) // index i in ls.tail => index i+1
        }
      }
    }

    val sco = scored.filter(s => {
      !firstLangInTag(s._1.tags, langs).isEmpty
    }).map(s => {
      (firstLangInTag(s._1.tags, langs).get * langSpread, s._2  )
    })

    //sco.filter( f => f._1 < 0 ).collect().foreach( f => println(f._1 + "=>" + f._2))
    //sco.filter( f => f._2 < 0 ).collect().foreach( f => println(f._1 + "=>" + f._2))
    sco
  }


  /** Sample the vectors */
  def sampleVectors(vectors: RDD[(LangIndex, HighScore)]): Array[(Int, Int)] = {

    assert(kmeansKernels % langs.length == 0, "kmeansKernels should be a multiple of the number of languages studied.")
    val perLang = kmeansKernels / langs.length

    // http://en.wikipedia.org/wiki/Reservoir_sampling
    def reservoirSampling(lang: Int, iter: Iterator[Int], size: Int): Array[Int] = {
      val res = new Array[Int](size)
      val rnd = new util.Random(lang)

      for (i <- 0 until size) {
        assert(iter.hasNext, s"iterator must have at least $size elements")
        res(i) = iter.next
      }

      var i = size.toLong
      while (iter.hasNext) {
        val elt = iter.next
        val j = math.abs(rnd.nextLong) % i
        if (j < size)
          res(j.toInt) = elt
        i += 1
      }

      res
    }

    val res =
      if (langSpread < 500)
      // sample the space regardless of the language
        vectors.takeSample(false, kmeansKernels, 42)
      else
      // sample the space uniformly from each language partition
        vectors.groupByKey.flatMap({
          case (lang, vectors) => reservoirSampling(lang, vectors.toIterator, perLang).map((lang, _))
        }).collect()

    assert(res.length == kmeansKernels, res.length)
    res
  }







  //
  //
  //  Kmeans utilities:
  //
  //

  /** Decide whether the kmeans clustering converged */
  def converged(distance: Double) =
    distance < kmeansEta


  /** Return the euclidean distance between two points */
  def euclideanDistance(v1: (Int, Int), v2: (Int, Int)): Double = {
    val part1 = (v1._1 - v2._1).toDouble * (v1._1 - v2._1)
    val part2 = (v1._2 - v2._2).toDouble * (v1._2 - v2._2)
    part1 + part2
  }

  /** Return the euclidean distance between two points */
  def euclideanDistance(a1: Array[(Int, Int)], a2: Array[(Int, Int)]): Double = {
    assert(a1.length == a2.length)
    var sum = 0d
    var idx = 0
    while (idx < a1.length) {
      sum += euclideanDistance(a1(idx), a2(idx))
      idx += 1
    }
    sum
  }

  /** Return the closest point */
  def findClosest(p: (Int, Int), centers: Array[(Int, Int)]): Int = {
    var bestIndex = 0
    var closest = Double.PositiveInfinity
    for (i <- 0 until centers.length) {
      val tempDist = euclideanDistance(p, centers(i))
      if (tempDist < closest) {
        closest = tempDist
        bestIndex = i
      }
    }
    bestIndex
  }

  /** Main kmeans computation */
  @tailrec final def kmeans(means: Array[(Int, Int)], vectors: RDD[(Int, Int)], iter: Int = 1, debug: Boolean = false): Array[(Int, Int)] = {
    val newMeans = means.clone() // you need to compute newMeans
    vectors.cache()
    /*newMeans.foreach( f => {
      println( f._1 + " => " + f._2)
    })*/
    val cluster: RDD[(Int, (Int, Int))] = vectors.map( v => {
      ( findClosest( v, means ), v )
    })

    //val clusterCount: RDD[(Int, (Int, Int, Int))] = cluster.mapValues( f => { (f._1, f._2, 1)})

    /*val cavg = clusterCount.reduceByKey( (a, b) => {
      ( a._1 + b._1, a._2 + b._2, a._3 + b._3)
    }).mapValues( f => {
      ( (f._1 / f._3).toInt , (f._2 / f._3).toInt )
    }).collect().toMap*/

    val cavg = cluster.groupByKey().mapValues(averageVectors).collect().toMap

    means.indices.foreach( x => {
      //println(x)
      newMeans.update(x, cavg.getOrElse(x, means(x)))
    })
    //println("=================================")
   /* newMeans.foreach( f => {
      println( f._1 + " => " + f._2)
    })*/

    val distance = euclideanDistance(means, newMeans)

    if (debug) {
      println(
        s"""Iteration: $iter
           |  * current distance: $distance
           |  * desired distance: $kmeansEta
           |  * means:""".stripMargin)
      for (idx <- 0 until kmeansKernels)
        println(f"   ${means(idx).toString}%20s ==> ${newMeans(idx).toString}%20s  " +
          f"  distance: ${euclideanDistance(means(idx), newMeans(idx))}%8.0f")
    }

    if (converged(distance))
      newMeans
    else if (iter < kmeansMaxIterations)
      kmeans(newMeans, vectors, iter + 1, debug)
    else {
      if (debug) {
        println("Reached max iterations!")
      }
      newMeans
    }
  }
  /** Average the vectors */
  def averageVectors(ps: Iterable[(Int, Int)]): (Int, Int) = {
    val iter = ps.iterator
    var count = 0
    var comp1: Long = 0
    var comp2: Long = 0
    while (iter.hasNext) {
      val item = iter.next
      comp1 += item._1
      comp2 += item._2
      count += 1
    }
    ((comp1 / count).toInt, (comp2 / count).toInt)
  }


  //
  //
  //  Displaying results:
  //
  //
  def clusterResults(means: Array[(Int, Int)], vectors: RDD[(LangIndex, HighScore)]): Array[(String, Double, Int, Int)] = {
    val closest: RDD[(Int, (LangIndex, HighScore))] = vectors.map(p => (findClosest(p, means), p))
    val closestGrouped: RDD[(Int, Iterable[(LangIndex, HighScore)])] = closest.groupByKey()

    val median = closestGrouped.mapValues { vs: Iterable[(LangIndex, HighScore)] => {
      val mapBuffer = scala.collection.mutable.Map[LangIndex, Int]()
      var langIndex = -1;
      var maxCount = -1;
      vs.foreach( f => {
        mapBuffer.put( f._1, mapBuffer.getOrElse(f._1, 0) + 1)
        if( maxCount < mapBuffer.getOrElse(f._1, 0)){
          langIndex = f._1;
          maxCount = mapBuffer.getOrElse(f._1, 0)
        }
      })
      val size = vs.count(_ => true)
      val scores = vs.map(k => k._2).toList.sortWith(_ < _)

      val middle = size/2
      val medianScore: Int =
        if(scores.size % 2 == 0)
          (scores(middle-1) + scores(middle)) / 2
        else scores(middle)



      val groupedLangSize: Map[Int, Int] = vs.map( _._1 / langSpread).groupBy(identity).mapValues(_.size)
      val maxLangIndex = groupedLangSize.maxBy( _._2 )._1


      val langLabel: String = langs( maxLangIndex ) // most common language in the cluster
      val langPercent: Double = (groupedLangSize(maxLangIndex) * 100)/(size * 1.0) // percent of the questions in the most common language
      val clusterSize: Int = size

      (langLabel, langPercent, clusterSize, medianScore)
    }
    }

    median.collect().map(_._2).sortBy(_._4)
  }

  def printResults(results: Array[(String, Double, Int, Int)]): Unit = {
    println("Resulting clusters:")
    println("  Score  Dominant language (%percent)  Questions")
    println("================================================")
    for ((lang, percent, size, score) <- results)
      println(f"${score}%7d  ${lang}%-17s (${percent}%-5.1f%%)      ${size}%7d")
  }
}
