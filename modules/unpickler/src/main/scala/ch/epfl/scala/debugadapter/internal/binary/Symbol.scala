package ch.epfl.scala.debugadapter.internal.binary

import ch.epfl.scala.debugadapter.internal.stacktrace.NameTransformer

trait Symbol:
  def name: String
  def sourceLines: Seq[SourceLine]

  def unexpandedDecodedNames: Seq[String] =
    val expanded = ".+\\$\\$(_\\$)*(.+)".r
    def unexpand(name: String) =
      name match
        case expanded(_, name) => name
        case _ => name
    Seq(name, NameTransformer.decode(unexpand(name)), unexpand(decodedName)).distinct

  def decodedName: String =
    NameTransformer.decode(name)
