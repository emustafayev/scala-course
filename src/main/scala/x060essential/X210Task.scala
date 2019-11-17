package x060essential

import scala.collection.immutable

object X210Task extends App {
  val assoc_subj_verb = Map(
    "Noel" -> List("wrote", "chased", "slept on"),
    "The cat" -> List("meowed at", "chased", "slept on"),
    "The dog" -> List("barked at", "chased", "slept on")
  )
  val assoc_verb_obj = Map(
    "wrote" -> List("the book", "the letter", "the code"),
    "chased" -> List("the ball", "the dog", "the cat"),
    "slept on" -> List("the bed", "the mat", "the train"),
    "meowed at" -> List("Noel", "the door", "the food cupboard"),
    "barked at" -> List("the postman", "the car", "the cat"),
  )
  val total: immutable.Iterable[String] = for {
    (s1, v1L) <- assoc_subj_verb // decompose to key, value
    v1 <- v1L                    // decompose List to Items
    (v2, o2L) <- assoc_verb_obj  // decompose to key, value
    o2 <- o2L                    // decompose List to Items
    if v1 == v2                  // make a filtering
  } yield s"$s1 $v1 $o2"         // produce a result
  total.foreach(println)
}
