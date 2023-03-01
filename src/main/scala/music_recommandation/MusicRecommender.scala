package music_recommandation

import java.io.{BufferedWriter, File, FileWriter}
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ParSeq

class MusicRecommender(private val parallel: Boolean = false, private val fileName: String = "train_triplets_2048.txt") {

  private def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)

  // load all songs
  private val songs = in.getLines().toList map (line => line split "\t" slice(1,2) mkString) distinct
  // load all users
  private val users = in.getLines().toList map (line => line split "\t" slice(0,1) mkString) distinct

  // TEST-ONLY: subset of all users and songs
  private val (usedSongs, usedUsers) = if(parallel) (songs.par slice(0,100), users.par slice(0,100)) else (songs slice(0,100), users)

  // given a user, it returns a list of all the songs (s)he listened to
  private def songsFilteredByUser(user:String) :List[String] = (for {
    line <- in.getLines().toList.filter(line => line.contains(user))
  } yield line split "\t" match {
    case Array(_, song, _) => song
  }) distinct

  // create a map user1->[song1, song2, ...], user2->[song3,...]
  private val usersToSongsMap = users map (user => (user, songsFilteredByUser(user))) toMap

  private def formula(specificFormula: (String, String) => Double): IterableOnce[(String, String, Double)] = {


    def rank(): IterableOnce[(String, String, Double)] = {
      for {
        u <- usedUsers
        s <- usedSongs //filter(song => !usersToSongsMap(u).contains(song))
        if !usersToSongsMap(u).contains(s)
      } yield {
        def rank: Double = specificFormula(u,s)
        (u, s, rank)
      }
    }

    rank()
  }

  private def time[R](block: => R, modelName: String, parallel: Boolean): R = {
    // get start time
    val t0 = System.nanoTime()
    // execute code
    val result = block
    // get end time
    val t1 = System.nanoTime()
    // print elapsed time
    println(s"Elapsed time for ${if(parallel) "parallel" else "sequential"} ${modelName}:\t" + (t1 - t0)/1000000 + "ms")
    // return the result
    result
  }

  def getItemBasedModelRank(outputFileName: String = "") = {
    // if the user listened to both songs return 1, else 0
    def numerator(song1: String, song2: String, user: String): Int =
      if (usersToSongsMap(user).contains(song1) && usersToSongsMap(user).contains(song2)) 1 else 0

    // it calculates the cosine similarity between two songs
    def cosineSimilarity(song1: String, song2: String): Double = {
      val usersTuples = usedUsers.iterator.map(user => (numerator(song1, song2, user),
        if (usersToSongsMap(user).contains(song1)) 1 else 0,
        if (usersToSongsMap(user).contains(song2)) 1 else 0
      ))
      val u = usersTuples.fold((0, 0, 0)) {
        (acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)
      }
      u._1 / (sqrt(u._2) * sqrt(u._3))
    }

    def specificFormula(user: String, song: String): Double = {
      for {
        s2 <- usedSongs //filter (s => s != song) //filter deprecated
        if s2 != song
      }
        yield {
          val listened = if(usersToSongsMap(user).contains(s2)) 1 else 0
          listened*cosineSimilarity(song, s2)
        }
    } sum

    val itemBasedModel = time(formula(specificFormula), "item-based model", parallel)
    if(outputFileName != "") writeModelOnFile(itemBasedModel, outputFileName)
  }

  def getUserBasedModelRank(outputFileName: String = "") = {
    // if both user listened to the same song return 1, else 0
    def numerator(user1:String, user2: String, song:String): Int =
      if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song)) 1 else 0

    // it calculates the cosine similarity between two users
    def cosineSimilarity(user1: String, user2: String): Double = {
      val usersTuples = usedSongs.iterator.map(song => (numerator(user1, user2, song),
        if (usersToSongsMap(user1).contains(song)) 1 else 0,
        if (usersToSongsMap(user2).contains(song)) 1 else 0,
      ))
      val u = usersTuples.fold((0, 0, 0)) {
        (acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)
      }
      val denominator = (sqrt(u._2) * sqrt(u._3))
      if (denominator != 0) u._1 / denominator
      else 0
    }

    def specificFormula(user: String, song: String): Double = {
      for {
        u2 <- usedUsers //filter (u => u != user)   // filter deprecated
        if u2 != user
      }
        yield {
          val listened = if(usersToSongsMap(u2).contains(song)) 1 else 0
          listened*cosineSimilarity(user, u2)
        }
    } sum

    val userBasedModel = time(formula(specificFormula), "user-based model", parallel)
    if(outputFileName != "") writeModelOnFile(userBasedModel, outputFileName)
  }

  private def writeModelOnFile(model: IterableOnce[(String, String, Double)], outputFileName: String = "")= {
    val f = new File(getClass.getClassLoader.getResource(outputFileName).getPath)
    val bw = new BufferedWriter(new FileWriter(f))
    // 1. CAST MODEL TO SEQ O PARSEQ
    // 2. ORDER IT IF IT'S SEQ
      //sorted(Ordering.by[(String, String, Double), Double](_._3) reverse)
    // 3. PRINT IT
    model.iterator.toSeq sorted(Ordering.by[(String, String, Double), Double](_._3) reverse) groupBy(_._1) map (el => {
      el._2 map (row => {
        bw.write(s"${row._1}\t${row._2}\t${row._3}\n")
      })
    })
    bw.close()
//    model.iterator.map
    /*
    model groupBy(_._1) map(el => {
      el._2 map (row => {
        bw.write(row._1 + "\t" + row._2 + "\t" + row._3 + "\n")
      })
    })
    bw.close()
    */
  }
}