package com.monovore.decline

import cats.Applicative
import cats.implicits._
import com.monovore.decline.Result._

/** A top-level argument parser, with all the info necessary to parse a full
  * set of arguments or display a useful help text.
  */
case class Command[A](
  name: String = "program",
  header: String,
  options: Opts[A]
) {

  def showHelp: String = Help.render(this)
}

/** A parser for zero or more command-line options.
  */
sealed trait Opts[A] {

  def mapValidated[B](fn: A => Result[B]): Opts[B] = this match {
    case Opts.Validate(a, v) => Opts.Validate(a, v andThen { _ andThen fn })
    case other => Opts.Validate(other, fn)
  }

  def map[B](fn: A => B) =
    mapValidated(fn andThen success)

  def withDefault[A0](default: => A0)(implicit isOption: A <:< Option[A0]) =
    map { _.getOrElse(default) }

  def validate(message: String)(fn: A => Boolean) = mapValidated { a =>
    if (fn(a)) success(a) else failure(message)
  }

  def parse(args: Seq[String]): Result[A] = Parse.apply(args.toList, this)
}

object Opts {

  sealed trait Name
  case class LongName(flag: String) extends Name { override val toString: String = s"--$flag"}
  case class ShortName(flag: Char) extends Name { override val toString: String = s"-$flag"}

  private[this] def namesFor(long: String, short: String): List[Name] = List(LongName(long)) ++ short.toList.map(ShortName(_))

  case class Pure[A](a: A) extends Opts[A]
  case class App[A, B](f: Opts[A => B], a: Opts[A]) extends Opts[B]
  case class Single[A, B](opt: Opt[A], help: String)(val read: A => Result[B]) extends Opts[B]
  case class Subcommands[A](commands: List[Command[A]]) extends Opts[A]
  case class Validate[A, B](value: Opts[A], validate: A => Result[B]) extends Opts[B]

  implicit val applicative: Applicative[Opts] =
    new Applicative[Opts] {
      override def pure[A](x: A): Opts[A] = Opts.Pure(x)
      override def ap[A, B](ff: Opts[A => B])(fa: Opts[A]): Opts[B] = Opts.App(ff, fa)
    }

  private[this] def metavarFor[A](provided: String)(implicit arg: Argument[A]) =
    if (provided.isEmpty) arg.defaultMetavar else provided

  def required[A : Argument](long: String, help: String, short: String = "", metavar: String = ""): Opts[A] =
    Single(Opt.Regular(namesFor(long, short), metavarFor[A](metavar)), help) {
      case Nil => failure(s"Missing mandatory option: --$long")
      case first :: Nil => Argument[A].read(first)
      case _ => failure(s"Too many values for option: --$long")
    }

  def optional[A : Argument](long: String, help: String, short: String = "", metavar: String = ""): Opts[Option[A]] =
    Single(Opt.Regular(namesFor(long, short), metavarFor[A](metavar)), help) {
      case Nil => success(None)
      case first :: Nil => Argument[A].read(first).map(Some(_))
      case _ => failure(s"Too many values for option: --$long")
    }

  def repeated[A : Argument](long: String, help: String, short: String = "", metavar: String = ""): Opts[List[A]] =
    Single(Opt.Regular(namesFor(long, short), metavarFor[A](metavar)), help) { list =>
      Applicative[Result].sequence(list.map(Argument[A].read))
    }

  def flag(long: String, help: String, short: String = ""): Opts[Boolean] =
    Single(Opt.Flag(namesFor(long, short)), help) {
      case 0 => success(false)
      case _ => success(true)
    }

  def requiredArg[A : Argument](metavar: String = ""): Opts[A] =
    Single(Opt.Arguments(metavarFor[A](metavar)), "Unused.") {
      case List(arg) => Argument[A].read(arg)
      case Nil => failure(s"Missing positional argument: $metavar")
    }

  def optionalArg[A : Argument](metavar: String = ""): Opts[Option[A]] =
    Single(Opt.Arguments(metavarFor[A](metavar)), "Unused.") {
      case List(arg) => Argument[A].read(arg).map(Some(_))
      case Nil => success(None)
    }

  def remainingArgs[A : Argument](metavar: String = ""): Opts[List[A]] =
    Single(Opt.Arguments(metavarFor[A](metavar), Int.MaxValue), "Unused.") { list =>
      Applicative[Result].sequence(list.map(Argument[A].read))
    }

  val help =
    Single(Opt.Flag(namesFor("help", "")), "Display this help text") {
      case 0 => success(())
      case _ => failure()
    }

  def command[A](name: String, help: String)(opts: Opts[A]): Command[A] = Command(name, help, opts)

  def subcommands[A](commands: Command[A]*): Opts[A] = Subcommands(commands.toList)
}

sealed trait Opt[A]

object Opt {

  import Opts.Name

  case class Regular(names: List[Name], metavar: String) extends Opt[List[String]]
  case class Flag(names: List[Name]) extends Opt[Int]
  case class Arguments(metavar: String, limit: Int = 1) extends Opt[List[String]] {
    require(limit > 0, "Requested number of arguments should be positive.")
  }
}