package hackerrank.d200515_09

object AlternatingCharactersApp extends App {

  def alternatingCharacters(s: String): Int =
    s.foldLeft((0,'_')) { (a, c) =>
      if (a._2 != c) (a._1,   c)
      else           (a._1+1, c)
    }._1

  println(alternatingCharacters("AABAAB"))

}
