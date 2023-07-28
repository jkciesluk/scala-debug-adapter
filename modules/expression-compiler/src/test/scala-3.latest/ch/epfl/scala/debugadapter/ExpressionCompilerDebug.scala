package ch.epfl.scala.debugadapter

import ch.epfl.scala.debugadapter.testfmk.TestingDebuggee
import ch.epfl.scala.debugadapter.ScalaVersion
import dotty.tools.dotc.ExpressionCompilerBridge
import java.nio.file.Files
import scala.collection.mutable.Buffer
import java.{util => ju}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

/**
 * This class is used to enter the expression compiler with a debugger
 *  It is not meant to be run in the CI
 */
class ExpressionCompilerDebug extends munit.FunSuite:
  val scalaVersion = ScalaVersion.`3.1+`
  val compiler = new ExpressionCompilerBridge

  override def munitTimeout: Duration = 1.hour

  test("debug expression compiler".ignore) {
    val javaSource =
      """|package example;
         |
         |class A {
         |  protected static String x = "x";
         |  protected static String m() {
         |    return "m";
         |  }
         |}
         |""".stripMargin
    val javaModule = TestingDebuggee.fromJavaSource(javaSource, "example.A", scalaVersion)
    val scalaSource =
      """|package example
         |
         |object Main extends A {
         |  def main(args: Array[String]): Unit = {
         |    println("Hello, World!")
         |  }
         |}
         |""".stripMargin
    implicit val debuggee: TestingDebuggee =
      TestingDebuggee.mainClass(scalaSource, "example.Main", scalaVersion, Seq.empty, Seq(javaModule.mainModule))
    evaluate(5, "A.x")
    evaluate(5, "A.m")
  }

  def evaluate(line: Int, expression: String, localVariables: Set[String] = Set.empty)(using
      debuggee: TestingDebuggee
  ): Unit =
    val out = debuggee.tempDir.resolve("expr-classes")
    if Files.notExists(out) then Files.createDirectory(out)
    val errors = Buffer.empty[String]
    compiler.run(
      out,
      "Expression",
      debuggee.classPathString,
      debuggee.mainModule.scalacOptions.toArray,
      debuggee.mainSource,
      line,
      expression,
      localVariables.asJava,
      "example",
      error => {
        println(Console.RED + error + Console.RESET)
        errors += error
      },
      testMode = true
    )
    if errors.nonEmpty then throw new Exception("Evaluation failed")