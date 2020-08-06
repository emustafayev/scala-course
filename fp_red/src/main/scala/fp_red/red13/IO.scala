package fp_red.red13

import scala.annotation.tailrec
import scala.io.StdIn
import scala.language.postfixOps

/**
  * minimal version of IO which is able to represent output and composability.
  *
  * It's a Monoid. it has `empty` and `++` operation.
  */
object IO0 {
  trait IO { self =>
    def run: Unit
    def ++(io: IO): IO = new IO {
      def run = { self.run; io.run }
    }
  }
  object IO {
    def empty: IO = new IO { def run = () }
  }

  def fahrenheitToCelsius(f: Double): Double = (f - 32) * 5.0/9.0

  /**
    * but, we still can't represent input
    */
  def converter: Unit = {
    println("Enter a temperature in degrees Fahrenheit: ")
    val d = StdIn.readLine.toDouble
    println(fahrenheitToCelsius(d))
  }
}

/**
  * next version of IO which can represent user's input,
  * and can be composed via flatMap/map.
  *
  * now it has form a Monad
  *
  * It still has a problems:
  *
  * - StackOverflow;
  * - A value of type IO[A] is completely opaque (it’s too general)
  */
object IO1 {
  sealed trait IO[A] { self =>
    def run: A
    def map[B](f: A => B): IO[B] =
      new IO[B] { def run = f(self.run) }
    def flatMap[B](f: A => IO[B]): IO[B] =
      new IO[B] { def run = f(self.run).run }
  }

  object IO extends Monad[IO] {
    def unit[A](a: => A): IO[A] = new IO[A] { def run = a }
    def flatMap[A,B](fa: IO[A])(f: A => IO[B]) = fa flatMap f
    def apply[A](a: => A): IO[A] = unit(a) // syntax for IO { .. }

    def ref[A](a: A): IO[IORef[A]] = IO { new IORef(a) }

    /** mutable state management, already wrapped into IO */
    sealed class IORef[A](var value: A) {
      def set(a: A): IO[A] = IO { value = a; a }
      def get: IO[A] = IO { value }
      def modify(f: A => A): IO[A] = get flatMap (a => set(f(a)))
    }
  }

  def readLine: IO[String] = IO { StdIn.readLine }
  def printLine(msg: String): IO[Unit] = IO { println(msg) }
  import IO0.fahrenheitToCelsius

  def converter: IO[Unit] = for {
    _ <- printLine("Enter a temperature in degrees Fahrenheit: ")
    d <- readLine.map(_.toDouble)
    c = fahrenheitToCelsius(d)
    s = c.toString
    _ <- printLine(s)
  } yield ()
  /** to run this: */
  //converter.run

  import IO._

  val echo: IO[Unit] = readLine.flatMap(printLine)
  val readInt: IO[Int] = readLine.map(_.toInt)
  val readInts: IO[(Int,Int)] = readInt ** readInt
  val fivePrompts: IO[Unit] = replicateM_(5)(converter)
  val lines: IO[List[String]] = replicateM(10)(readLine)

  /**
    * Larger example using various monadic combinators
    */
  val helpString = """
  | The Amazing Factorial REPL, v2.0
  | q - quit
  | <number> - compute the factorial of the given number
  | <anything else> - bomb with horrible error
  """.trim.stripMargin

  def factorial(n: Int): IO[Int] = for {
    acc <- ref(1)
    //               stream 1,2,...n  f: A => IO[Unit]
    _   <- foreachM (1 to n toStream) (i => acc.modify(x => x * i).skip)
    res <- acc.get
  } yield res

  val factorialREPL: IO[Unit] =
    printLine(helpString) *>
    doWhile { readLine } { line =>
      when (line != "q") { for {
        i <- IO { line.toInt }
        n <- factorial(i)
        m = s"factorial of $i is equal to $n"
        _ <- printLine(m)
      } yield () }
    }
}

/**
  * fixing stack overflow problem
  */
object IO2a {

  sealed trait IO[A] {
    /**
      * - here we build new description of operation
      * - we do not interpret the `flatMap` here, just return it as a value
      * - we will do this later
      */
    def flatMap[B](f: A => IO[B]): IO[B] = FlatMap(this, f)
    def map[B](f: A => B): IO[B] = flatMap(f andThen (Return(_)))
  }
  /** will be used for non-recursive calls */
  case class Return[A](a: A) extends IO[A]
  /** will be used for lazy initialization */
  case class Suspend[A](resume: () => A) extends IO[A]
  /** will be used for recursive calls */
  case class FlatMap[A,B](sub: IO[A], k: A => IO[B]) extends IO[B]

  object IO extends Monad[IO] { // Notice that none of these operations DO anything
    def unit   [A](a: => A)                 : IO[A] = Return(a)
    def suspend[A](a: => IO[A])             : IO[A] = Suspend(() => ()).flatMap { _ => a }
    def flatMap[A,B](a: IO[A])(f: A => IO[B]): IO[B] = a flatMap f
  }

  def printLine(s: String): IO[Unit] = Suspend(() => Return(println(s)))

  val p = IO.forever(printLine("Still going..."))

  val actions: Stream[IO[Unit]] = Stream.fill(100000)(printLine("Still going..."))
  val composite: IO[Unit] = actions.foldLeft(IO.unit(())) { (acc, a) => acc flatMap { _ => a } }

  // There is only one sensible way to implement this as a
  // tail-recursive function, the one tricky case is left-nested
  // flatMaps, as in `((a flatMap f) flatMap g)`, which we
  // reassociate to the right as `a flatMap (ar => f(a) flatMap g)`
  @tailrec
  def run[A](io: IO[A]): A = io match {
    case Return(a) => a     // A
    case Suspend(r) => r()  // A
    case FlatMap(x, f) =>
      val step = x match {
        case Return(a) => f(a)
        case Suspend(r) => f(r())
        case FlatMap(y, g) => y.flatMap(a => g(a).flatMap(f))
      }
      run(step)
  }
}

object IO2aTests {
  import IO2a.IO
  import IO2a.run

  type IO[A] = IO2a.IO[A]
  val f: Int => IO[Int] = (i: Int) => IO2a.Return(i)

  val list: List[Int => IO[Int]] = List.fill(10000)(f)
  /**
    * we fold list of functions to one nested function
    * for further usage
    */
  val g: Int => IO[Int] =
    list.foldLeft(f) {
      //   accumulator  ,   list element
      (acc: Int => IO[Int], fn: Int => IO[Int]) =>
        (x: Int) => IO.suspend(acc(x).flatMap(fn))
    }

  def main(args: Array[String]): Unit = {
    // we pass the value 42 through the combination of 10k functions
    val g42: IO[Int] = g(42)
    val r: Int = run(g42)
    println(s"g(42): Function = $g42")
    println(s"run(g(42)): Value = $r")
  }
}

/**
  * As it turns out, there's nothing about this data type that is specific
  * to I/O, it's just a general purpose data type for optimizing tail calls.
  * Here it is, renamed to `TailRec`. This type is also sometimes called
  * `Trampoline`, because of the way interpreting it bounces back and forth
  * between the main `run` loop and the functions contained in the `TailRec`.
  */
object IO2b {

  sealed trait TailRec[A] {
    def flatMap[B](f: A => TailRec[B]): TailRec[B] = FlatMap(this, f)
    def map[B](f: A => B): TailRec[B] = flatMap(f andThen { x: B => Return(x) })
  }
  case class Return[A](a: A) extends TailRec[A]
  case class Suspend[A](resume: () => A) extends TailRec[A]
  case class FlatMap[A,B](sub: TailRec[A], k: A => TailRec[B]) extends TailRec[B]

  object TailRec extends Monad[TailRec] {
    def unit[A](a: => A): TailRec[A] = Return(a)
    def flatMap[A,B](a: TailRec[A])(f: A => TailRec[B]): TailRec[B] = a flatMap f
    def suspend[A](a: => TailRec[A]): TailRec[A] = Suspend(() => ()).flatMap { _ => a }
  }

  @tailrec
  def run[A](t: TailRec[A]): A = t match {
    case Return(a) => a
    case Suspend(r) => r()
    case FlatMap(x, f) =>
      val step = x match {
        case Return(a) => f(a)
        case Suspend(r) => f(r())
        case FlatMap(y, g) => y.flatMap(a => g(a).flatMap(f))
      }
      run(step)
  }
}

object IO2bTests {
  import IO2b._

  val f: Int => TailRec[Int] = (i: Int) => Return(i)

  val list: List[Int => TailRec[Int]] = List.fill(10000)(f)
  /**
    * we fold list of functions to one nested function
    * for further usage
    */
  val g: Int => TailRec[Int] =
    list.foldLeft(f) {
       //   accumulator        ,      list element
      (acc: Int => TailRec[Int], fn: Int => TailRec[Int]) =>
        (x: Int) => TailRec.suspend(acc(x).flatMap(fn))
    }

  def main(args: Array[String]): Unit = {
    // we pass the value 42 through the combination of 10k functions
    val g42: TailRec[Int] = g(42)
    val r: Int = run(g42)
    println(s"g(42): Function = $g42")
    println(s"run(g(42)): Value = $r")
  }
}

/**
  * 13.4.
  *
  * We've solved our first problem of ensuring stack safety, but we're still
  * being very inexplicit about what sort of effects can occur, and we also
  * haven't found a way of describing asynchronous computations. Our `Suspend
  * thunks will just block the current thread when run by the interpreter.
  * We could fix that by changing the signature of `Suspend` to take a `Par`.
  * We'll call this new type `Async`.
  */
object IO2c {

  import fp_red.red07.Nonblocking._

  sealed trait Async[A] { // will rename this type to `Async`
    def flatMap[B](f: A => Async[B]): Async[B] = FlatMap(this, f)
    def map[B](f: A => B): Async[B] = flatMap(f andThen (Return(_)))
  }
  case class Return[A](a: A) extends Async[A]
  case class Suspend[A](resume: Par[A]) extends Async[A] // notice this is a `Par`
  case class FlatMap[A,B](sub: Async[A], k: A => Async[B]) extends Async[B]

  object Async extends Monad[Async] {
    def unit[A](a: => A): Async[A] = Return(a)
    def flatMap[A,B](a: Async[A])(f: A => Async[B]): Async[B] = a flatMap f
  }

  // return either a `Suspend`, a `Return`, or a right-associated `FlatMap`
  @tailrec
  def step[A](async: Async[A]): Async[A] = async match {
    case FlatMap(FlatMap(x, f), g) => step(x flatMap (a => f(a) flatMap g))
    case FlatMap(Return(x), f) => step(f(x))
    case _ => async
  }

  def run[A](async: Async[A]): Par[A] = step(async) match {
    case Return(a) => Par.unit(a)
    case Suspend(r) => r
    case FlatMap(x, f) => x match {
      case Suspend(r) => Par.flatMap(r)(a => run(f(a)))
      case _ => sys.error("Impossible, since `step` eliminates these cases")
    }
  }

  /**
    * The fact that `run` only uses the `unit` and `flatMap` functions of
    * `Par` is a clue that choosing `Par` was too specific of a choice,
    * this interpreter could be generalized to work with any monad.
    */
}

/**
  * We can generalize `TailRec` and `Async` to the type `Free`, which is
  * a `Monad` for any choice of `F`.
  */
object IO3 {

  sealed trait Free[F[_],A] {
    def flatMap[B](f: A => Free[F,B]): Free[F,B] =
      FlatMap(this, f)
    def map[B](f: A => B): Free[F,B] =
      flatMap(f andThen (Return(_)))
  }
  case class Return[F[_],A](a: A) extends Free[F, A]
  case class Suspend[F[_],A](s: F[A]) extends Free[F, A]
  case class FlatMap[F[_],A,B](s: Free[F, A],
                               f: A => Free[F, B]) extends Free[F, B]

  // Exercise 1: Implement the free monad
  def freeMonad[F[_]]: Monad[({type f[a] = Free[F,a]})#f] = ???

  // Exercise 2: Implement a specialized `Function0` interpreter.
  // @annotation.tailrec
  def runTrampoline[A](a: Free[Function0,A]): A = ???

  // Exercise 3: Implement a `Free` interpreter which works for any `Monad`
  def run[F[_],A](a: Free[F,A])(implicit F: Monad[F]): F[A] = ???

  // return either a `Suspend`, a `Return`, or a right-associated `FlatMap`
  // @annotation.tailrec
  def step[F[_],A](a: Free[F,A]): Free[F,A] = ???

  /*
  The type constructor `F` lets us control the set of external requests our
  program is allowed to make. For instance, here is a type that allows for
  only console I/O effects.
  */

  import fp_red.red07.Nonblocking.Par
//  import fpinscala.parallelism.Nonblocking.Par

  sealed trait Console[A] {
    def toPar: Par[A]
    def toThunk: () => A

    // other interpreters
    def toState: ConsoleState[A]
    def toReader: ConsoleReader[A]
  }

  case object ReadLine extends Console[Option[String]] {
    def toPar = Par.lazyUnit(run)
    def toThunk = () => run

    def run: Option[String] =
      try Some(StdIn.readLine())
      catch { case e: Exception => None }

    def toState = ConsoleState { bufs =>
      bufs.in match {
        case List() => (None, bufs)
        case h :: t => (Some(h), bufs.copy(in = t))
      }
    }
    def toReader = ConsoleReader { in => Some(in) }
  }

  case class PrintLine(line: String) extends Console[Unit] {
    def toPar = Par.lazyUnit(println(line))
    def toThunk = () => println(line)
    def toReader = ConsoleReader { s => () } // noop
    def toState = ConsoleState { bufs => ((), bufs.copy(out = bufs.out :+ line)) } // append to the output
  }

  object Console {
    type ConsoleIO[A] = Free[Console, A]

    def readLn: ConsoleIO[Option[String]] =
      Suspend(ReadLine)

    def printLn(line: String): ConsoleIO[Unit] =
      Suspend(PrintLine(line))
  }

  /*
  How do we actually _run_ a `ConsoleIO` program? We don't have a `Monad[Console]`
  for calling `run`, and we can't use `runTrampoline` either since we have `Console`,
  not `Function0`. We need a way to translate from `Console` to `Function0`
  (if we want to evaluate it sequentially) or a `Par`.

  We introduce the following type to do this translation:
  */

  /* Translate between any `F[A]` to `G[A]`. */
  trait Translate[F[_], G[_]] { def apply[A](f: F[A]): G[A] }

  type ~>[F[_], G[_]] = Translate[F,G] // gives us infix syntax `F ~> G` for `Translate[F,G]`

  implicit val function0Monad = new Monad[Function0] {
    def unit[A](a: => A) = () => a
    def flatMap[A,B](a: Function0[A])(f: A => Function0[B]) =
      () => f(a())()
  }

  implicit val parMonad = new Monad[Par] {
    def unit[A](a: => A) = Par.unit(a)
    def flatMap[A,B](a: Par[A])(f: A => Par[B]) = Par.fork { Par.flatMap(a)(f) }
  }

  def runFree[F[_],G[_],A](free: Free[F,A])(t: F ~> G)(
                           implicit G: Monad[G]): G[A] =
    step(free) match {
      case Return(a) => G.unit(a)
      case Suspend(r) => t(r)
      case FlatMap(Suspend(r), f) => G.flatMap(t(r))(a => runFree(f(a))(t))
      case _ => sys.error("Impossible, since `step` eliminates these cases")
    }

  val consoleToFunction0 =
    new (Console ~> Function0) { def apply[A](a: Console[A]) = a.toThunk }
  val consoleToPar =
    new (Console ~> Par) { def apply[A](a: Console[A]) = a.toPar }

  def runConsoleFunction0[A](a: Free[Console,A]): () => A =
    runFree[Console,Function0,A](a)(consoleToFunction0)
  def runConsolePar[A](a: Free[Console,A]): Par[A] =
    runFree[Console,Par,A](a)(consoleToPar)

  /*
  The `runConsoleFunction0` implementation is unfortunately not stack safe,
  because it relies of the stack safety of the underlying monad, and the
  `Function0` monad we gave is not stack safe. To see the problem, try
  running: `freeMonad.forever(Console.printLn("Hello"))`.
  */

  // Exercise 4 (optional, hard): Implement `runConsole` using `runFree`,
  // without going through `Par`. Hint: define `translate` using `runFree`.

  def translate[F[_],G[_],A](f: Free[F,A])(fg: F ~> G): Free[G,A] = ???

  def runConsole[A](a: Free[Console,A]): A = ???

  /*
  There is nothing about `Free[Console,A]` that requires we interpret
  `Console` using side effects. Here are two pure ways of interpreting
  a `Free[Console,A]`.
  */
  import Console._

  case class Buffers(in: List[String], out: Vector[String])

  // A specialized state monad
  case class ConsoleState[A](run: Buffers => (A, Buffers)) {
    def map[B](f: A => B): ConsoleState[B] =
      ConsoleState { s =>
        val (a, s1) = run(s)
        (f(a), s1)
      }
    def flatMap[B](f: A => ConsoleState[B]): ConsoleState[B] =
      ConsoleState { s =>
        val (a, s1) = run(s)
        f(a).run(s1)
      }
  }
  object ConsoleState {
    implicit val monad = new Monad[ConsoleState] {
      def unit[A](a: => A) = ConsoleState(bufs => (a,bufs))
      def flatMap[A,B](ra: ConsoleState[A])(f: A => ConsoleState[B]) = ra flatMap f
    }
  }

  // A specialized reader monad
  case class ConsoleReader[A](run: String => A) {
    def map[B](f: A => B): ConsoleReader[B] =
      ConsoleReader(r => f(run(r)))
    def flatMap[B](f: A => ConsoleReader[B]): ConsoleReader[B] =
      ConsoleReader(r => f(run(r)).run(r))
  }
  object ConsoleReader {
    implicit val monad = new Monad[ConsoleReader] {
      def unit[A](a: => A) = ConsoleReader(_ => a)
      def flatMap[A,B](ra: ConsoleReader[A])(f: A => ConsoleReader[B]) = ra flatMap f
    }
  }

  val consoleToState =
    new (Console ~> ConsoleState) { def apply[A](a: Console[A]) = a.toState }
  val consoleToReader =
    new (Console ~> ConsoleReader) { def apply[A](a: Console[A]) = a.toReader }

  /* Can interpet these as before to convert our `ConsoleIO` to a pure value that does no I/O! */
  def runConsoleReader[A](io: ConsoleIO[A]): ConsoleReader[A] =
    runFree[Console,ConsoleReader,A](io)(consoleToReader)

  def runConsoleState[A](io: ConsoleIO[A]): ConsoleState[A] =
    runFree[Console,ConsoleState,A](io)(consoleToState)

  // So `Free[F,A]` is not really an I/O type. The interpreter `runFree` gets
  // to choose how to interpret these `F` requests, and whether to do "real" I/O
  // or simply convert to some pure value!

  // NB: These interpretations are not stack safe for the same reason,
  // can instead work with `case class ConsoleReader[A](run: String => Trampoline[A])`,
  // which gives us a stack safe monad

  // We conclude that a good representation of an `IO` monad is this:
  type IO[A] = Free[Par, A]

  /*
   * Exercise 5: Implement a non-blocking read from an asynchronous file channel.
   * We'll just give the basic idea - here, we construct a `Future`
   * by reading from an `AsynchronousFileChannel`, a `java.nio` class
   * which supports asynchronous reads.
   */

  import java.nio.channels._

  def read(file: AsynchronousFileChannel,
           fromPosition: Long,
           numBytes: Int): Par[Either[Throwable, Array[Byte]]] = ???

  // Provides the syntax `Async { k => ... }` for asyncronous IO blocks.
  def Async[A](cb: (A => Unit) => Unit): IO[A] =
    Suspend(Par.async(cb))

  // Provides the `IO { ... }` syntax for synchronous IO blocks.
  def IO[A](a: => A): IO[A] = Suspend { Par.delay(a) }
}
