package cats.kleisli_valid

import cats.data.Kleisli
import cats.instances.list._

object KleisliIntroApp extends App {

  val step1: Kleisli[List, Int, Int] = Kleisli(x => List(x + 1, x - 1))
  val step2: Kleisli[List, Int, Int] = Kleisli(x => List(x, -x))
  val step3: Kleisli[List, Int, Int] = Kleisli(x => List(x * 2, x / 2))
  val pipeline = step1 andThen step2 andThen step3

  val r: List[Int] = pipeline.run(20)
  println(r)

  val f0 = (x: Int) => List(-x, +x)
  val f1 = (x: Int) => List(x-0.5, x+0.5)
  val f2 = (x: Double) => List(s"$x!", s"$x?")

  val k0 = Kleisli { f0 }
  val k1 = Kleisli { f1 }
  val k2 = Kleisli { f2 }

  val s01 = k0 andThen k1
  val s12 = k1 andThen k2
  val s012 = k0 andThen k1 andThen k2
  val s012a = k0 map { _ + 1000 } andThen k1 andThen k2

  val fm01 = (x: List[Int]) => x.flatMap(f0).flatMap(f1).flatMap(f2)

  val r012a: List[String] = fm01(List(10))
  val r012b: List[String] = s012.run(10)
  val r012c: List[String] = s012a.run(10)
  println(r012a)
  println(r012b)
  println(r012c)
}
